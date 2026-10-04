package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test

class MediaCatalogTest {
    private val source = LibrarySource(LibrarySourceKind.AUDIO, "content://docs/tree/one")
    @Test fun `pages enumerate a stable snapshot with terminal null cursor`() {
        val catalog = MediaCatalog((0..4).map { testEntry(it.toString(), source) })
        val first = catalog.page(2, 0, null)
        val second = catalog.page(2, first.nextOffset!!, first.revision)
        val last = catalog.page(2, second.nextOffset!!, first.revision)
        assertEquals(5, first.total)
        assertEquals(listOf("0", "1", "2", "3", "4"), (first.items + second.items + last.items).map { it.id })
        assertNull(last.nextOffset)
        assertTrue(catalog.page(2, Int.MAX_VALUE, first.revision).items.isEmpty())
    }
    @Test fun `different publication invalidates pagination`() {
        val old = MediaCatalog(listOf(testEntry("a", source)))
        val next = MediaCatalog(listOf(testEntry("a", source)))
        assertThrows(CatalogRevisionChanged::class.java) { next.page(500, 0, old.revision) }
    }
    @Test fun `invalid pagination is rejected`() {
        val catalog = MediaCatalog(emptyList())
        listOf(0, -1, 501).forEach { limit -> assertThrows(IllegalArgumentException::class.java) { catalog.page(limit, 0, null) } }
        assertThrows(IllegalArgumentException::class.java) { catalog.page(500, -1, null) }
    }
}
