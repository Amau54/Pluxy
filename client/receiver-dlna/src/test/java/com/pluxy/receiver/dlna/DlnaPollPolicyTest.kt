package com.pluxy.receiver.dlna

import com.pluxy.receiver.core.RemotePlaybackPhase
import org.junit.Assert.*
import org.junit.Test

class DlnaPollPolicyTest {
    @Test fun `transport state determines phase even when position cannot advance`() {
        val policy = DlnaPollPolicy()
        assertEquals(RemotePlaybackPhase.PAUSED, policy.phase("PAUSED_PLAYBACK"))
        assertEquals(RemotePlaybackPhase.PLAYING, policy.phase("PLAYING"))
        assertEquals(RemotePlaybackPhase.BUFFERING, policy.phase("TRANSITIONING"))
        assertEquals(RemotePlaybackPhase.ENDED, policy.phase("STOPPED"))
        assertEquals(RemotePlaybackPhase.ENDED, policy.phase("NO_MEDIA_PRESENT"))
        assertThrows(IllegalStateException::class.java) { policy.phase("garbage") }
    }
    @Test fun `consecutive failures terminate but recovery resets the budget`() {
        val policy = DlnaPollPolicy()
        assertFalse(policy.failed()); assertFalse(policy.failed())
        policy.success()
        assertFalse(policy.failed()); assertFalse(policy.failed()); assertTrue(policy.failed())
    }
}
