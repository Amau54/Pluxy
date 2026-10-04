package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaylistTest {
    @Test fun `disc and track metadata beat alphabetic titles`() {
        val first = track("Intro", 1, 1)
        val final = track("Finale", 2, 1)
        val secondDisc = track("Bonus", 1, 2)
        assertEquals(listOf("Intro", "Finale", "Bonus"),
            orderedAlbumTracks(listOf(secondDisc, final, first), first).map { it.title })
    }
    @Test fun `legacy filenames provide numeric order and exclude other albums`() {
        val first = track("Intro", null, null, "01 Intro.mp3")
        val later = track("Finale", null, null, "10 Finale.mp3")
        val middle = track("Bridge", null, null, "02 Bridge.mp3")
        val other = first.copy(id = "other", album = "Other")
        assertEquals(listOf("Intro", "Bridge", "Finale"),
            orderedAlbumTracks(listOf(later, other, middle, first), first).map { it.title })
    }
    @Test fun `invalid numbers fall back to stable normalized title order`() {
        val z = track("Zebra", -1, 0)
        val a = track("Élan", 0, -1)
        assertEquals(listOf("Élan", "Zebra"), orderedAlbumTracks(listOf(z, a), z).map { it.title })
    }
    @Test fun `autoqueue skips unavailable track between available tracks`() {
        val first = track("Intro", 1, 1)
        val missing = track("Bridge", 2, 1).copy(available = false)
        val last = track("Finale", 3, 1)
        assertEquals(listOf("Intro", "Finale"),
            orderedAlbumTracks(listOf(last, missing, first), first).map { it.title })
    }
    @Test fun `explicit unavailable selection stays in queue to report its error`() {
        val first = track("Intro", 1, 1)
        val selected = track("Bridge", 2, 1).copy(available = false)
        val otherMissing = track("Bonus", 3, 1).copy(available = false)
        val last = track("Finale", 4, 1)
        assertEquals(listOf("Intro", "Bridge", "Finale"),
            orderedAlbumTracks(listOf(last, selected, otherMissing, first), selected).map { it.title })
    }
    private fun track(title: String, number: Int?, disc: Int?, path: String = "$title.mp3") = MediaItem(
        id = title, title = title, path = path, container = "mp3", size = 1, duration = 100.0,
        kind = "audio", artist = "Artist", album = "Album", trackNumber = number, discNumber = disc,
    )
}
