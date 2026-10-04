package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test

internal fun testEntry(id: String, source: LibrarySource, available: Boolean = true) = MediaEntry(
    id = id, uri = if (source.isAutomatic) "content://media/external/audio/media/$id" else "${source.treeUri}/document/$id",
    fileName = "$id.mp3", title = id, parsedYear = null, container = "mp3", mimeType = "audio/mpeg", size = 10,
    modifiedAtMs = 1, durationSeconds = 1.0, width = null, height = null, videoCodec = null, audioCodec = "mp3",
    audioChannels = 2, isHdr = false, kind = "audio", seriesName = null, seriesKey = null, season = null, episode = null,
    artist = null, album = null, folder = null, subtitles = emptyList(), sourceKey = source.identity(), available = available)

class ScanMergePolicyTest {
    private val one = LibrarySource(LibrarySourceKind.AUDIO, "content://docs/tree/one")
    private val two = LibrarySource(LibrarySourceKind.AUDIO, "content://docs/tree/two")
    @Test fun `partial source preserves missing entries and successful source deletes absent media`() {
        val old = listOf(testEntry("a", one), testEntry("b", two))
        val result = ScanMergePolicy.merge(old, emptyList(), listOf(one, two), setOf(one.identity()), 100)
        assertEquals(listOf("a"), result.map { it.id })
        assertFalse(result.single().available)
    }
    @Test fun `restored source republishes availability without changing identity`() {
        val old = testEntry("a", one, false)
        val fresh = testEntry("a", one)
        assertEquals(listOf(fresh), ScanMergePolicy.merge(listOf(old), listOf(fresh), listOf(one), emptySet(), 100))
    }
    @Test fun `automatic sources without permissions retain their catalogue as unavailable`() {
        val sources = LibrarySourceKind.entries.map { LibrarySource.automatic(it) }
        val cached = sources.mapIndexed { index, source ->
            testEntry("cached$index", source).copy(kind = source.kind.publicKind)
        }
        val result = ScanMergePolicy.merge(cached, emptyList(), sources, sources.map { it.identity() }.toSet(), 100)
        assertEquals(cached.map { it.id }, result.map { it.id })
        assertTrue(result.all { !it.available })
    }
    @Test fun `removed source is discarded even when another source is incomplete`() {
        assertTrue(ScanMergePolicy.merge(listOf(testEntry("a", one)), emptyList(), listOf(two), setOf(two.identity()), 100).isEmpty())
    }
    @Test fun `legacy SAF entries are preserved when their selected tree is inaccessible`() {
        val old = testEntry("a", one).copy(sourceKey = null)
        val result = ScanMergePolicy.merge(listOf(old), emptyList(), listOf(one), setOf(one.identity()), 100)
        assertEquals(one.identity(), result.single().sourceKey)
        assertFalse(result.single().available)
    }
    @Test fun `scan cap preserves known media before admitting new entries`() {
        val old = listOf(testEntry("a", one), testEntry("b", one))
        val result = ScanMergePolicy.merge(old, listOf(testEntry("c", one)), listOf(one), setOf(one.identity()), 2)
        assertEquals(listOf("a", "b"), result.map { it.id })
    }
    @Test fun `track and disc tags support totals and reject malformed values`() {
        assertEquals(3, audioNumber("03/12"))
        assertEquals(2, audioNumber(" 2 "))
        assertNull(audioNumber("0")); assertNull(audioNumber("-2")); assertNull(audioNumber("side A"))
    }
}
