package com.pluxy.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeCountdownTest {
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
