package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPresentationTest {
    @Test fun `numero complet lisible sans dupliquer le titre de repli`() {
        val episode = media("Rick.S01E01.MULTI.1080p.WEB-DL.H264-FTMVHD")
            .copy(seriesName = "Rick et Morty", season = 1, episode = 1)
        assertEquals("Saison 1 - Épisode 1", episodeCode(episode))
        assertEquals("Rick et Morty · Saison 1 - Épisode 1", LibraryOrganizer.playbackTitle(episode))
    }

    @Test fun `saison speciale et episode sans saison restent lisibles`() {
        assertEquals("Saison 0 - Épisode 3", episodeCode(media("Demo").copy(season = 0)))
        assertEquals("Saison 12 - Épisode 3", episodeCode(media("Demo").copy(season = 12)))
        assertEquals("Épisode 3", episodeCode(media("Demo").copy(season = null)))
    }
    @Test fun `episode sans titre ne montre pas les tags de publication`() {
        assertEquals("Épisode 3", cleanEpisodeTitle(media("Rick.and.Morty.S02E03.MULTI.1080p.WEB-DL.H264-FTMVHD"), 2))
    }

    @Test fun `titre episode conserve ses mots et retire le codec et le groupe`() {
        val episode = media("Rick.et.Morty.S08E03.La Summer de toutes les peurs x265-Amen")
            .copy(seriesName = "Rick et Morty", metadataTitle = "Rick et Morty", season = 8)
        assertEquals("La Summer de toutes les peurs", cleanEpisodeTitle(episode, 2))
        assertEquals("Rick et Morty · Saison 8 - Épisode 3 · La Summer de toutes les peurs", LibraryOrganizer.playbackTitle(episode))
        assertEquals("Un nouveau départ", cleanEpisodeTitle(media("Demo.S02E03.Un nouveau départ"), 2))
        assertEquals("French Connection", cleanEpisodeTitle(media("Demo.S02E03.French Connection"), 2))
    }

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
