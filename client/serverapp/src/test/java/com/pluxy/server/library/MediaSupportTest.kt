package com.pluxy.server.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSupportTest {
    @Test
    fun automaticSourceUsesMediaStoreSentinel() {
        val source = LibrarySource.automatic(LibrarySourceKind.PHOTO)

        assertTrue(source.isAutomatic)
        assertEquals(LibrarySource.AUTOMATIC_MEDIASTORE_URI, source.treeUri)
        assertEquals(LibrarySourceKind.PHOTO, source.kind)
    }

    @Test
    fun `extensions are normalized before classification`() {
        assertEquals("heic", MediaSupport.extension("Vacances.HEIC"))
        assertTrue(MediaSupport.supports(LibrarySourceKind.PHOTO, "heic"))
        assertFalse(MediaSupport.supports(LibrarySourceKind.AUDIO, "heic"))
    }

    @Test
    fun `source allowlists keep media tabs isolated`() {
        assertTrue(MediaSupport.supports(LibrarySourceKind.VIDEO, "mkv"))
        assertTrue(MediaSupport.supports(LibrarySourceKind.PHOTO, "webp"))
        assertTrue(MediaSupport.supports(LibrarySourceKind.AUDIO, "flac"))
        assertFalse(MediaSupport.supports(LibrarySourceKind.PHOTO, "mp4"))
        assertFalse(MediaSupport.supports(LibrarySourceKind.VIDEO, "mp3"))
    }

    @Test
    fun `fallback mime types cover each media family`() {
        assertEquals("image/jpeg", MediaSupport.mimeFor("photo", "jpg"))
        assertEquals("audio/flac", MediaSupport.mimeFor("audio", "flac"))
        assertEquals("video/x-matroska", MediaSupport.mimeFor("movie", "mkv"))
        assertEquals("application/octet-stream", MediaSupport.mimeFor("audio", "exe"))
    }
}
