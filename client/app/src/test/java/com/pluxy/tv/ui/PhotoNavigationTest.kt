package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.*
import org.junit.Test

class PhotoNavigationTest {
    @Test fun `missing folder recovers photos without merging distinct accented folders`() {
        val items = listOf(photo("a", "Cafe"), photo("b", "Café"))
        assertEquals("", validPhotoFolder(items, "Vacances"))
        assertEquals("Café", validPhotoFolder(items, "Café"))
        assertEquals("", validPhotoFolder(emptyList(), "Cafe"))
    }
    @Test fun `current photo survives a reordered catalogue and disappears safely`() {
        val items = listOf(photo("b", "Album"), photo("a", "Album"))
        assertEquals(1, restoredPhotoIndex(items, "a"))
        assertEquals(0, restoredPhotoIndex(items, "deleted"))
    }
    @Test fun `slow or failed image never starts slideshow interval`() {
        assertFalse(shouldAdvanceSlideshow(true, true, false, 3))
        assertTrue(shouldAdvanceSlideshow(true, true, true, 3))
        assertFalse(shouldAdvanceSlideshow(true, false, true, 3))
        assertFalse(shouldAdvanceSlideshow(false, true, true, 3))
        assertFalse(shouldAdvanceSlideshow(true, true, true, 1))
    }
    @Test fun `same folder names in different sources remain separate and preserve image order`() {
        val items = listOf(photo("b", "Vacances").copy(folderId = "disk-a", sourceName = "Disque A"),
            photo("a", "Vacances").copy(folderId = "disk-a", sourceName = "Disque A"),
            photo("c", "Vacances").copy(folderId = "disk-b", sourceName = "Disque B"))
        val pages = photoFolderPages(items)
        assertEquals(2, pages.size)
        assertEquals(listOf("b", "a"), pages[0].photos.map { it.id })
        assertEquals("Disque B / Vacances", pages[1].label)
    }
    @Test fun `prefetch includes nearby images and adjacent folders without current image or duplicates`() {
        val items = (1..8).map { photo("a$it", "A") } + listOf(photo("b1", "B"), photo("c1", "C"))
        val pages = photoFolderPages(items)
        val ids = nearbyPhotoIds(pages, pages[0].photos, 0)
        assertEquals(listOf("a2", "a8", "a3", "a7", "b1", "c1"), ids)
        assertTrue(nearbyPhotoIds(emptyList(), emptyList(), 0).isEmpty())
    }
    @Test fun `prefetch from single image excludes unavailable folder and current image`() {
        val items = listOf(photo("a", "A"), photo("b", "B").copy(available = false))
        val pages = photoFolderPages(items)
        assertEquals(1, pages.size)
        assertTrue(nearbyPhotoIds(pages, pages[0].photos, 0).isEmpty())
    }
    private fun photo(id: String, folder: String) = MediaItem(
        id = id, title = id, path = "$folder/$id.jpg", container = "jpg", size = 1, duration = 0.0,
        kind = "photo", folder = folder,
    )
}
