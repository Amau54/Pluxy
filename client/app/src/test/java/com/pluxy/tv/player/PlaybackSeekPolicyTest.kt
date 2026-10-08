package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSeekPolicyTest {
    @Test fun resumedPipeUsesAbsoluteMovieTime() {
        assertEquals(35000L, PlaybackSeekPolicy.absolutePosition(30000, 5000))
        assertEquals(30000L, PlaybackSeekPolicy.absolutePosition(30000, -1))
        assertEquals(1000L, PlaybackSeekPolicy.absolutePosition(0, 1000))
    }
    @Test fun onlyRemuxUrlsUseServerTimeSeeks() {
        assertTrue(PlaybackSeekPolicy.isRemux("/stream/remux/film?copy_audio=true"))
        assertFalse(PlaybackSeekPolicy.isRemux("/stream/direct/film"))
        assertFalse(PlaybackSeekPolicy.isRemux("/stream/hls/film/main/index.m3u8"))
    }
}
