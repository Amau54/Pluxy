package com.pluxy.receiver.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Commands belong to the session scope; rotating the sending screen does not cancel a load. */
class ReceiverSession(private val scope: CoroutineScope) {
    private val operations = Mutex()
    private val guard = Any()
    private var generation = 0L
    private var controller: ReceiverController? = null
    private var observation: Job? = null
    private val mutable = MutableStateFlow(RemotePlaybackStatus())
    val state = mutable.asStateFlow()

    suspend fun load(next: ReceiverController, media: RemoteMedia, playWhenReady: Boolean = true) = command(onStale = { next.close() }) {
        val expected = synchronized(guard) { generation }
        val old = synchronized(guard) { controller }
        if (old !== next) {
            if (old != null) {
                try { old.stop() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                detach(old)
                old.close()
            }
            synchronized(guard) {
                if (generation != expected) { next.close(); throw CancellationException("Session libérée") }
                controller = next
                observation = scope.launch {
                    next.status.collect { status ->
                        val terminal = status.media != null && status.phase in setOf(RemotePlaybackPhase.ENDED, RemotePlaybackPhase.ERROR)
                        val owned = synchronized(guard) {
                            if (controller !== next || generation != expected) false else {
                                mutable.value = status
                                if (terminal) { controller = null; observation = null }
                                true
                            }
                        }
                        if (owned && terminal) { next.close(); throw CancellationException("Lecture terminée") }
                    }
                }
            }
        }
        try { next.load(media, playWhenReady) } catch (error: Throwable) {
            synchronized(guard) {
                if (controller === next && mutable.value.phase != RemotePlaybackPhase.ERROR) {
                    mutable.value = next.status.value.copy(media = media, phase = RemotePlaybackPhase.ERROR,
                        error = error.message ?: "Échec de la diffusion")
                }
            }
            detach(next)
            next.close()
            throw error
        }
    }

    suspend fun playPause() = sessionCommand { active ->
        if (state.value.phase == RemotePlaybackPhase.PLAYING) active.pause() else active.play()
    }

    suspend fun seekTo(positionMs: Long) = sessionCommand { active ->
        active.seekTo(positionMs)
    }

    suspend fun showMedia(media: RemoteMedia) = sessionCommand { active ->
        active.load(media, true)
    }

    suspend fun stop() = sessionCommand { active ->
        try { active.stop() } finally {
            detach(active)
            active.close()
            synchronized(guard) { if (controller == null) mutable.value = RemotePlaybackStatus() }
        }
    }

    /** Invalidates in-flight operations before synchronously releasing the owner. */
    fun release() {
        val old = synchronized(guard) {
            generation++
            observation?.cancel(); observation = null
            controller.also { controller = null; mutable.value = RemotePlaybackStatus() }
        }
        old?.close()
    }

    private fun detach(value: ReceiverController) = synchronized(guard) {
        if (controller === value) { observation?.cancel(); observation = null; controller = null }
    }

    private suspend fun sessionCommand(block: suspend (ReceiverController) -> Unit) {
        val owner = synchronized(guard) { controller } ?: return
        command {
            if (synchronized(guard) { controller === owner }) block(owner)
        }
    }

    private suspend fun <T> command(onStale: () -> Unit = {}, block: suspend () -> T): T {
        val expected = synchronized(guard) { generation }
        return scope.async {
            operations.withLock {
                synchronized(guard) {
                    if (generation != expected) { onStale(); throw CancellationException("Session libérée") }
                }
                block()
            }
        }.await()
    }
}
