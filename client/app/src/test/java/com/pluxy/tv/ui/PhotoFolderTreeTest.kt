package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.*
import org.junit.Test

class PhotoFolderTreeTest {
    private fun photo(id: String, source: String, path: String) = MediaItem(id, id, "", "jpg", 1, 0.0,
        kind = "photo", sourceId = source, sourceName = "Photos", relativePath = path)

    @Test fun `toutes les racines et les descendants apparaissent dans l ordre`() {
        val photos = listOf(photo("a", "disk1", "2024/Voyage/a.jpg"), photo("b", "disk1", "2024/b.jpg"),
            photo("c", "disk2", "2024/Voyage/c.jpg"), photo("d", "disk1", "d.jpg"))
        val rows = PhotoFolderTree.rows(photos)
        assertEquals(listOf("d", "b", "a", "c"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        val headers = rows.filterIsInstance<LibRow.Header>()
        assertEquals(listOf(0, 1, 2, 0, 1, 2), headers.map { it.depth })
        assertTrue(headers.all { it.expanded })
        assertEquals(headers.size, headers.map { it.id }.distinct().size)
    }

    @Test fun `replier un parent masque seulement sa descendance`() {
        val photos = listOf(photo("a", "one", "Voyage/a.jpg"), photo("b", "two", "Voyage/b.jpg"))
        val rows = PhotoFolderTree.rows(photos, setOf("photo-tree:one"))
        assertEquals(listOf("b"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        assertFalse((rows.first() as LibRow.Header).expanded)
        assertEquals("folders", TvLibraryContent.Query("photo").photoView)
    }

    @Test fun `la rubrique videos ne contient ni films ni episodes`() {
        val video = photo("video", "one", "video.mp4").copy(kind = "clip", duration = 30.0)
        val rows = TvLibraryContent.rows(listOf(video, video.copy(id = "movie", kind = "movie"),
            video.copy(id = "series", kind = "series")), TvLibraryContent.Query("clip"))
        assertEquals(listOf("video"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        assertEquals(ArtworkPolicy.Aspect.LANDSCAPE, ArtworkPolicy.plan("clip").fallbackAspect)
    }

    @Test fun `les photos suivent le tri choisi a l interieur du dossier`() {
        val photos = listOf(photo("alpha", "one", "Voyage/alpha.jpg").copy(addedAt = 1.0),
            photo("beta", "one", "Voyage/beta.jpg").copy(addedAt = 2.0))
        val rows = PhotoFolderTree.rows(photos, query = TvLibraryContent.Query("photo", sort = "added"))
        assertEquals(listOf("beta", "alpha"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }
}
