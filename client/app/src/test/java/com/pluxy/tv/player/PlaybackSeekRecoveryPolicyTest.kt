package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSeekRecoveryPolicyTest {
    @Test fun `audio reset is limited to direct Dolby DTS or forced output`() {
        assertTrue(PlaybackSeekRecoveryPolicy.needsAudioReset(true,"audio/true-hd",false))
        assertTrue(PlaybackSeekRecoveryPolicy.needsAudioReset(true,"audio/eac3-joc",false))
        assertTrue(PlaybackSeekRecoveryPolicy.needsAudioReset(true,null,true))
        assertFalse(PlaybackSeekRecoveryPolicy.needsAudioReset(true,"audio/mp4a-latm",false))
        assertFalse(PlaybackSeekRecoveryPolicy.needsAudioReset(false,"audio/ac3",true))
    }
    @Test fun `full buffer after seek recovers without waiting for a network timeout`() {
        assertTrue(PlaybackSeekRecoveryPolicy.renderedBufferStuck(6_000,45_000,true,true))
        assertFalse(PlaybackSeekRecoveryPolicy.renderedBufferStuck(5_000,45_000,true,true))
        assertFalse(PlaybackSeekRecoveryPolicy.renderedBufferStuck(20_000,500,true,true))
        assertFalse(PlaybackSeekRecoveryPolicy.renderedBufferStuck(20_000,45_000,false,true))
        assertFalse(PlaybackSeekRecoveryPolicy.renderedBufferStuck(20_000,6_000,true,true,networkLoading=true))
    }
    @Test fun `recovery is bounded and never selects a video conversion`() {
        val policy=PlaybackSeekRecoveryPolicy
        assertEquals(PlaybackSeekRecoveryPolicy.Action.REBUILD,policy.action(true,true,false,true,0))
        assertEquals(PlaybackSeekRecoveryPolicy.Action.TEMPORAL_COPY,policy.action(true,true,false,true,1))
        assertEquals(PlaybackSeekRecoveryPolicy.Action.NORMAL_FALLBACK,policy.action(true,true,false,true,2))
        assertEquals(PlaybackSeekRecoveryPolicy.Action.NORMAL_FALLBACK,policy.action(true,true,true,true,1))
        assertEquals(PlaybackSeekRecoveryPolicy.Action.NORMAL_FALLBACK,policy.action(true,true,false,false,1))
        assertEquals(PlaybackSeekRecoveryPolicy.Action.NORMAL_FALLBACK,policy.action(false,true,false,true,0))
    }
}
