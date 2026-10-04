package com.pluxy.server

import com.pluxy.receiver.core.ReceiverController
import com.pluxy.receiver.core.ReceiverSession
import com.pluxy.receiver.core.RemoteMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A process-owned session survives rotation and serializes all receiver commands. */
object RemotePlaybackRuntime {
    private val session = ReceiverSession(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    val state = session.state
    suspend fun load(controller: ReceiverController, media: RemoteMedia) = session.load(controller, media)
    suspend fun playPause() = session.playPause()
    suspend fun seekTo(positionMs: Long) = session.seekTo(positionMs)
    suspend fun showMedia(media: RemoteMedia) = session.showMedia(media)
    suspend fun stop() = session.stop()
    fun release() = session.release()
}
