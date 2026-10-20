package com.pluxy.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeCountdownTest {
    @Test fun `offer uses only the last five seconds of a known media timeline`() {
        assertNull(episodeEndRemainingMs(60_000, 54_999))
        assertEquals(5_000L, episodeEndRemainingMs(60_000, 55_000))
        assertEquals(2_500L, episodeEndRemainingMs(60_000, 57_500))
        assertEquals(0L, episodeEndRemainingMs(60_000, 60_000))
        assertNull(episodeEndRemainingMs(0, 0))
        assertNull(episodeEndRemainingMs(-1, 0))
        assertNull(episodeEndRemainingMs(60_000, -1))
    }

    @Test fun `countdown starts at five and rounds up partial seconds`() {
        assertEquals(5, episodeCountdownSeconds(10_000L, 5_000L))
        assertEquals(5, episodeCountdownSeconds(10_000L, 5_050L))
        assertEquals(4, episodeCountdownSeconds(10_000L, 6_000L))
        assertEquals(1, episodeCountdownSeconds(10_000L, 9_999L))
    }

    @Test fun `delayed UI ticks do not extend autoplay`() {
        assertEquals(4, episodeCountdownSeconds(10_000L, 6_500L))
        assertEquals(0, episodeCountdownSeconds(10_000L, 10_000L))
        assertEquals(0, episodeCountdownSeconds(10_000L, 12_000L))
    }
}
