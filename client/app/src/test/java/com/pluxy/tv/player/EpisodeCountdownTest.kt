package com.pluxy.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeCountdownTest {
    @Test fun `countdown starts at eight and rounds up partial seconds`() {
        assertEquals(8, episodeCountdownSeconds(10_000L, 2_000L))
        assertEquals(8, episodeCountdownSeconds(10_000L, 2_050L))
        assertEquals(7, episodeCountdownSeconds(10_000L, 3_000L))
        assertEquals(1, episodeCountdownSeconds(10_000L, 9_999L))
    }

    @Test fun `delayed UI ticks do not extend autoplay`() {
        assertEquals(4, episodeCountdownSeconds(10_000L, 6_500L))
        assertEquals(0, episodeCountdownSeconds(10_000L, 10_000L))
        assertEquals(0, episodeCountdownSeconds(10_000L, 12_000L))
    }
}
