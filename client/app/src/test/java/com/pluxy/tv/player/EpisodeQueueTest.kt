package com.pluxy.tv.player

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeQueueTest {
    @Test
    fun `next episode crosses seasons in numeric order`() {
        val current = episode("e2", 1, 2)
        val library = listOf(episode("e3", 2, 1), current, episode("e1", 1, 1))
        assertEquals("e3", EpisodeQueue.next(current, library)?.id)
        assertNull(EpisodeQueue.next(library.first(), library))
    }

    @Test
    fun `episodes from another series never enter the queue`() {
        val current = episode("a1", 1, 1)
        val other = episode("b2", 1, 2, key = "other")
        assertNull(EpisodeQueue.next(current, listOf(current, other)))
    }

    @Test
    fun `canonical series key comparison ignores case and whitespace`() {
        val current = episode("a1", 1, 1, key = " Show-ID ")
        val next = episode("a2", 1, 2, key = "show-id")
        assertEquals("a2", EpisodeQueue.next(current, listOf(next, current))?.id)
    }

    private fun episode(id: String, season: Int, number: Int, key: String = "show") = MediaItem(
        id = id,
        title = "Episode $number",
        path = "",
        container = "mkv",
        size = 1,
        duration = 1.0,
        kind = "series",
        seriesName = "Show",
        seriesKey = key,
        season = season,
        episode = number,
    )
}
