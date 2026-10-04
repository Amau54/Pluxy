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
    private fun photo(id: String, folder: String) = MediaItem(
        id = id, title = id, path = "$folder/$id.jpg", container = "jpg", size = 1, duration = 0.0,
        kind = "photo", folder = folder,
    )
}
