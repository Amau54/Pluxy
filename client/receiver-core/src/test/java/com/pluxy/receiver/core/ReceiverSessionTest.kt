package com.pluxy.receiver.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ReceiverSessionTest {
    private val media = RemoteMedia("one", "Film", "http://192.168.1.2/one", "video/mp4", "movie", "mp4")
    private class FakeReceiver : ReceiverController {
        override val status = MutableStateFlow(RemotePlaybackStatus())
        var loads = 0; var stops = 0; var closes = 0
        var loading: CompletableDeferred<Unit>? = null
        var stopping: CompletableDeferred<Unit>? = null
        override suspend fun load(media: RemoteMedia, playWhenReady: Boolean) {
            loads++
            status.value = RemotePlaybackStatus(media = media, phase = RemotePlaybackPhase.CONNECTING)
            loading?.await()
            status.value = status.value.copy(phase = if (playWhenReady) RemotePlaybackPhase.PLAYING else RemotePlaybackPhase.PAUSED)
        }
        override suspend fun play() { status.value = status.value.copy(phase = RemotePlaybackPhase.PLAYING) }
        override suspend fun pause() { status.value = status.value.copy(phase = RemotePlaybackPhase.PAUSED) }
        override suspend fun seekTo(positionMs: Long) { status.value = status.value.copy(positionMs = positionMs) }
        override suspend fun stop() { stops++; stopping?.await(); status.value = status.value.copy(phase = RemotePlaybackPhase.ENDED) }
        override fun close() { closes++ }
    }

    @Test fun `screen cancellation does not cancel owned load`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ReceiverSession(scope)
        try {
            val receiver = FakeReceiver().apply { loading = CompletableDeferred() }
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { session.load(receiver, media) }
            assertEquals(1, receiver.loads)
            caller.cancelAndJoin()
            assertEquals(0, receiver.closes)
            receiver.loading!!.complete(Unit)
            assertEquals(RemotePlaybackPhase.PLAYING, session.state.value.phase)
        } finally { session.release(); scope.cancel() }
    }

    @Test fun `stale queued stop never targets replacement receiver`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ReceiverSession(scope)
        try {
            val first = FakeReceiver()
            val next = FakeReceiver()
            session.load(first, media)
            first.stopping = CompletableDeferred()
            val replace = async(start = CoroutineStart.UNDISPATCHED) { session.load(next, media.copy(id = "two")) }
            val staleStop = async(start = CoroutineStart.UNDISPATCHED) { session.stop() }
            assertEquals(0, next.loads)
            first.stopping!!.complete(Unit)
            withTimeout(1000) { replace.await(); staleStop.await() }
            assertEquals(1, first.stops)
            assertEquals(1, next.loads)
            assertEquals(0, next.stops)
            assertEquals("two", session.state.value.media?.id)
        } finally { session.release(); scope.cancel() }
    }

    @Test fun `release invalidates queued replacement and suppresses late status`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ReceiverSession(scope)
        try {
            val first = FakeReceiver().apply { loading = CompletableDeferred() }
            val next = FakeReceiver()
            val loading = async(start = CoroutineStart.UNDISPATCHED) { session.load(first, media) }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { session.load(next, media) }
            session.release()
            first.loading!!.complete(Unit)
            loading.await()
            try { queued.await(); fail("queued replacement must be invalidated") } catch (_: CancellationException) { }
            assertEquals(0, next.loads)
            assertEquals(1, next.closes)
            assertEquals(RemotePlaybackPhase.IDLE, session.state.value.phase)
        } finally { session.release(); scope.cancel() }
    }

    @Test fun `terminal status releases controller and preserves useful error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ReceiverSession(scope)
        try {
            val receiver = FakeReceiver()
            session.load(receiver, media)
            receiver.status.value = receiver.status.value.copy(phase = RemotePlaybackPhase.ERROR, error = "TV disconnected")
            assertTrue(receiver.closes > 0)
            assertEquals("TV disconnected", session.state.value.error)
            session.seekTo(1000)
            assertEquals(0L, receiver.status.value.positionMs)
        } finally { session.release(); scope.cancel() }
    }
}
