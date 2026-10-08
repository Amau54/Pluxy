package com.pluxy.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferWatchPolicyTest {
    @Test fun downloadingAnIndexIsProgressEvenWithoutBufferedMediaTime() {
        assertFalse(PlaybackBufferWatchPolicy.isStuck(40_000, 1_000, false))
    }

    @Test fun directReadWithoutProgressIsBounded() {
        assertTrue(PlaybackBufferWatchPolicy.isStuck(20_000, 20_000, false))
    }

    @Test fun continuousBytesCannotHideAnUnplayableFileForever() {
        assertTrue(PlaybackBufferWatchPolicy.isStuck(90_000, 1_000, false))
    }

    @Test fun slowHlsGetsMoreTimeButNeverAnInfiniteWait() {
        assertFalse(PlaybackBufferWatchPolicy.isStuck(90_000, 5_000, true))
        assertTrue(PlaybackBufferWatchPolicy.isStuck(180_000, 5_000, true))
        assertTrue(PlaybackBufferWatchPolicy.isStuck(45_000, 45_000, true))
    }
}
