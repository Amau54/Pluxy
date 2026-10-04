package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeOrganizerTest {
    @Test
    fun `reprendre utilise la derniere lecture et exclut les medias termines`() {
        val old = media("old", inProgress = true, progress = .4, lastPlayed = 10.0)
        val recent = media("recent", inProgress = true, progress = .6, lastPlayed = 20.0)
        val watched = media("watched", watched = true, lastPlayed = 30.0)

        assertEquals(listOf("recent", "old"), HomeOrganizer.continueWatching(listOf(old, recent, watched)).map { it.id })
    }

    @Test
    fun `a suivre choisit le premier episode non vu sans dupliquer une reprise`() {
        val watched = episode("s1e1", 1, watched = true, lastPlayed = 40.0)
        val next = episode("s1e2", 2)
        val later = episode("s1e3", 3)
        val active = episode("other1", 1, series = "other", inProgress = true, progress = .5)
        val otherNext = episode("other2", 2, series = "other")

        assertEquals(
            listOf(next.id),
            HomeOrganizer.nextEpisodes(listOf(later, active, otherNext, next, watched)).map { it.id },
        )
    }

    @Test
    fun `une serie jamais commencee reste hors de a suivre`() {
        assertTrue(HomeOrganizer.nextEpisodes(listOf(episode("e1", 1), episode("e2", 2))).isEmpty())
    }

    @Test
    fun `reprise choisit dernier episode actif meme dans une autre saison`() {
        val old = episode("s1e1", 1, inProgress = true, lastPlayed = 10.0)
        val recent = episode("s2e3", 3, inProgress = true, lastPlayed = 20.0).copy(season = 2)
        val done = episode("done", 4, inProgress = true, watched = true, lastPlayed = 30.0)

        assertEquals("s2e3", HomeOrganizer.resumeEpisode(listOf(old, done, recent))?.id)
    }

    @Test
    fun `reprise apres fin de saison choisit premier non vu de saison suivante`() {
        val done = episode("s1e2", 2, watched = true)
        val first = episode("s1e1", 1, watched = true)
        val next = episode("s2e1", 1).copy(season = 2)
        val later = episode("s2e2", 2).copy(season = 2)

        assertEquals("s2e1", HomeOrganizer.resumeEpisode(listOf(later, done, next, first))?.id)
    }

    @Test
    fun `reprise garde un episode non vu plus ancien et ignore ordre entree`() {
        val hole = episode("e1", 1)
        val done = episode("e2", 2, watched = true)
        val later = episode("e3", 3)

        assertEquals("e1", HomeOrganizer.resumeEpisode(listOf(later, done, hole))?.id)
    }

    @Test
    fun `serie terminee recommence au debut et serie vide ne propose rien`() {
        val first = episode("s1e1", 1, watched = true)
        val later = episode("s2e1", 1, watched = true).copy(season = 2)

        assertEquals(first.id, HomeOrganizer.resumeEpisode(listOf(later, first))?.id)
        assertNull(HomeOrganizer.resumeEpisode(emptyList()))
    }

    private fun episode(
        id: String,
        number: Int,
        series: String = "demo",
        watched: Boolean = false,
        inProgress: Boolean = false,
        progress: Double = 0.0,
        lastPlayed: Double = 0.0,
    ) = media(
        id = id,
        kind = "series",
        series = series,
        episode = number,
        watched = watched,
        inProgress = inProgress,
        progress = progress,
        lastPlayed = lastPlayed,
    )

    private fun media(
        id: String,
        kind: String = "movie",
        series: String? = null,
        episode: Int? = null,
        watched: Boolean = false,
        inProgress: Boolean = false,
        progress: Double = 0.0,
        lastPlayed: Double = 0.0,
    ) = MediaItem(
        id = id,
        title = id,
        path = "",
        container = "mp4",
        size = 1,
        duration = 100.0,
        kind = kind,
        seriesName = series,
        seriesKey = series,
        season = if (kind == "series") 1 else null,
        episode = episode,
        watched = watched,
        inProgress = inProgress,
        progress = progress,
        lastPlayedAt = lastPlayed,
    )
}
