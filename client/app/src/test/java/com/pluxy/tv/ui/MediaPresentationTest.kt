package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPresentationTest {
    @Test
    fun `titre episode retire le marqueur technique`() {
        assertEquals(
            "Le commencement",
            cleanEpisodeTitle(media("Demo.S02E03.Le_commencement"), 2),
        )
    }

    @Test
    fun `temps de reprise gere films courts et longs`() {
        assertEquals("01:05", formatPlaybackTime(65_000))
        assertEquals("2:03:04", formatPlaybackTime(7_384_000))
    }

    @Test
    fun `episodes sans saison restent accessibles dans autres`() {
        val episodes = listOf(
            media("Numéroté"),
            media("Autre").copy(season = null, episode = null),
        )

        assertEquals(
            listOf("Autre"),
            episodesForSeason(episodes, selectedSeason = null, unnumberedSelected = true)
                .map { it.title },
        )
    }

    private fun media(title: String) = MediaItem(
        id = title,
        title = title,
        path = "",
        container = "mkv",
        size = 1,
        duration = 120.0,
        kind = "series",
        season = 2,
        episode = 3,
    )
}
