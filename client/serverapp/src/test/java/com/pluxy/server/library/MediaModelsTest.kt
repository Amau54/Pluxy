package com.pluxy.server.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.pluxy.server.WatchState
import org.json.JSONArray
import org.json.JSONObject

class MediaModelsTest {
    private val source = LibrarySource(LibrarySourceKind.VIDEO, "content://docs/tree/one")
    @Test
    fun `every visual media kind exposes a local thumbnail fallback`() {
        listOf("movie", "series", "photo", "audio").forEach { kind ->
            assertEquals("/image/thumbnail/media-id?v=2", localThumbnailUrl(kind, "media-id"))
        }
    }

    @Test
    fun `unknown media kind has no thumbnail endpoint`() {
        assertNull(localThumbnailUrl("document", "media-id"))
    }

    @Test fun `folder identity distinguishes sources and exact parent paths without exposing URIs`() {
        val first = folderIdentity("content://root/one", "content://root/one/Photos")
        assertEquals(first, folderIdentity("content://root/one", "content://root/one/Photos"))
        assertNotEquals(first, folderIdentity("content://root/two", "content://root/two/Photos"))
        assertNotEquals(first, folderIdentity("content://root/one", "content://root/one/Photos/Trip"))
        assertEquals(24, first.length)
    }

    @Test fun `matched metadata title is searchable without replacing the original filename title`() {
        val media = testEntry("abc", source).copy(kind = "movie", title = "File 123", parsedYear = 2020)
        val metadata = JSONObject().put("matched", true).put("title", "Le Voyage oublié")
            .put("year", 2021).put("genres", JSONArray(listOf("Drame", "Aventure")))
        val payload = media.publicJson(metadata, WatchState())
        assertEquals("File 123", payload.getString("title"))
        assertEquals("Le Voyage oublié", payload.getString("metadata_title"))
        assertEquals(listOf("Drame", "Aventure"), metadataGenres(metadata))
        assertEquals(2021, payload.getInt("year"))
        assertEquals("", payload.getString("path"))
    }

    @Test fun `unmatched or non video metadata never exposes an alternate title`() {
        val metadata = JSONObject().put("matched", false).put("title", "Fallback")
        assertEquals(JSONObject.NULL, testEntry("abc", source).copy(kind = "movie").publicJson(metadata, WatchState()).get("metadata_title"))
        metadata.put("matched", true)
        assertEquals(JSONObject.NULL, testEntry("abc", source).publicJson(metadata, WatchState()).get("metadata_title"))
    }

    @Test fun `dates survive persisted index roundtrip and legacy missing discovery retains ordering`() {
        val media = testEntry("abc", source).copy(addedAtMs = 123_000, modifiedAtMs = 456_000,
            capturedAtMs = 12_000, folderId = "folder-hash", relativePath = "Trip/file.jpg")
        val persisted = media.persistedJson()
        assertEquals(media, MediaEntry.fromPersistedJson(persisted))
        val payload = media.publicJson(null, WatchState())
        assertEquals(123.0, payload.getDouble("added_at"), 0.0)
        assertEquals(456.0, payload.getDouble("modified_at"), 0.0)
        assertEquals(12.0, payload.getDouble("captured_at"), 0.0)
        persisted.remove("added_at_ms")
        assertEquals(456_000L, MediaEntry.fromPersistedJson(persisted).addedAtMs)
        persisted.put("added_at_ms", 0)
        assertEquals(0L, MediaEntry.fromPersistedJson(persisted).addedAtMs)
    }
}
