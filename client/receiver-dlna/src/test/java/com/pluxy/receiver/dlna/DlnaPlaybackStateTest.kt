package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.ReceiverCapabilities
import com.pluxy.receiver.core.ReceiverDevice
import com.pluxy.receiver.core.ReceiverProtocol
import com.pluxy.receiver.core.RemoteMedia
import com.pluxy.receiver.core.RemotePlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaPlaybackStateTest {
    private val device = ReceiverDevice("tv", "TV", ReceiverProtocol.DLNA, capabilities = ReceiverCapabilities())
    private val movie = RemoteMedia("movie", "Film", "http://192.168.1.2/movie", "video/mp4", "movie", "mp4", durationMs = 7_200_000)

    @Test
    fun `loading the next movie replaces the previous duration and progress`() {
        val previous = DlnaPlaybackState.connecting(device, movie).copy(positionMs = 5_000_000, error = "Ancienne erreur")
        val next = movie.copy(id = "next", durationMs = 90_000)
        val loaded = DlnaPlaybackState.connecting(device, next)
        assertEquals(7_200_000L, previous.durationMs)
        assertEquals(next, loaded.media)
        assertEquals(90_000L, loaded.durationMs)
        assertEquals(0L, loaded.positionMs)
        assertEquals(RemotePlaybackPhase.CONNECTING, loaded.phase)
        assertNull(loaded.error)
    }

    @Test
    fun `photo or unknown duration cannot retain a movie duration`() {
        val photo = movie.copy(kind = "photo", durationMs = 0)
        assertEquals(0L, DlnaPlaybackState.connecting(device, photo).durationMs)
        assertEquals(0L, DlnaPlaybackState.connecting(device, photo.copy(durationMs = -1)).durationMs)
    }
}
