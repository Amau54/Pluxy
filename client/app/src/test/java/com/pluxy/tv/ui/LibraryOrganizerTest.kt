package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOrganizerTest {
    @Test
    fun `search covers tags folders and ignores accents`() {
        val track = item(
            id = "track",
            kind = "audio",
            title = "Titre",
            artist = "Beyoncé",
            album = "L'Été",
            folder = "Musique / Concerts",
        )
        assertTrue(LibraryOrganizer.matchesSearch(track, "beyonce"))
        assertTrue(LibraryOrganizer.matchesSearch(track, "concerts"))
        assertFalse(LibraryOrganizer.matchesSearch(track, "cinema"))
    }

    @Test
    fun `photo folder wins over date and missing date is explicit`() {
        val folderPhoto = item("p1", "photo", folder = "Voyages / Rome", addedAt = 1.0)
        val unknownPhoto = item("p2", "photo", addedAt = 0.0)
        assertEquals("folder:voyages / rome", LibraryOrganizer.photoAlbumKey(folderPhoto))
        assertEquals("Voyages / Rome", LibraryOrganizer.photoAlbumLabel(folderPhoto))
        assertEquals("date:unknown", LibraryOrganizer.photoAlbumKey(unknownPhoto))
        assertEquals("Photos sans date", LibraryOrganizer.photoAlbumLabel(unknownPhoto))
    }

    @Test
    fun `untagged audio queue stays inside its folder`() {
        val first = item("a", "audio", folder = "Musique / Album A")
        val same = item("b", "audio", folder = "musique / album a")
        val other = item("c", "audio", folder = "Musique / Album B")
        assertTrue(LibraryOrganizer.sameAudioAlbum(first, same))
        assertFalse(LibraryOrganizer.sameAudioAlbum(first, other))
    }

    @Test
    fun `prepared title sort keeps accent insensitive stable order and evaluates once`() {
        val values = listOf("Zulu", "Été", "ete", "Álpha", "alpha", "Beta")
        var evaluated = 0
        val expected = values.sortedBy(LibraryOrganizer::searchKey)

        val actual = LibraryOrganizer.sortedBySearchKey(values) { value ->
            evaluated += 1
            value
        }

        assertEquals(listOf("Álpha", "alpha", "Beta", "Été", "ete", "Zulu"), actual)
        assertEquals(expected, actual)
        assertEquals(values.size, evaluated)
    }

    private fun item(
        id: String,
        kind: String,
        title: String = id,
        artist: String? = null,
        album: String? = null,
        folder: String? = null,
        addedAt: Double = 0.0,
    ) = MediaItem(
        id = id,
        title = title,
        path = "",
        container = "bin",
        size = 1,
        duration = 1.0,
        kind = kind,
        artist = artist,
        album = album,
        folder = folder,
        addedAt = addedAt,
    )
}
