package com.pluxy.server.library

import com.pluxy.server.WatchState
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowseCatalogTest {
    @Test fun `title pages follow the title displayed from metadata`() {
        val catalog = LibraryBrowseCatalog("titles", listOf(
            entry("a", "Zulu", metadata = BrowseMetadata(title = "Alpha")),
            entry("b", "Alpha", metadata = BrowseMetadata(title = "Zulu")),
            entry("c", "Bravo", metadata = BrowseMetadata(title = " ")),
        ))
        val first = catalog.page(1, 0, null, query("sort" to "title"))
        val rest = catalog.page(2, first.nextOffset!!, first.revision, query("sort" to "title"))
        assertEquals(listOf("a", "c", "b"), (first.items + rest.items).map { it.media.id })
    }
    private val source = LibrarySource(LibrarySourceKind.VIDEO, "content://documents/tree/films")
    private fun entry(id: String, title: String = id, kind: String = "movie", added: Long = 100,
        modified: Long = added, duration: Double = 5400.0, width: Int? = 1920, height: Int? = 1080,
        hdr: Boolean = false, metadata: BrowseMetadata = BrowseMetadata(2024, listOf("Drame")),
        watch: WatchState = WatchState()): BrowseEntry = BrowseEntry(
        testEntry(id, source).copy(title = title, kind = kind, addedAtMs = added, modifiedAtMs = modified,
            durationSeconds = duration, width = width, height = height, isHdr = hdr), metadata, watch)
    private fun query(vararg values: Pair<String, String>) = LibraryQuery.fromParameters(mapOf(*values))
    private fun ids(catalog: LibraryBrowseCatalog, query: LibraryQuery) = catalog.page(500, 0, null, query).items.map { it.media.id }

    @Test fun `all filter dimensions combine while repeated genres use OR`() {
        val catalog = LibraryBrowseCatalog("one", listOf(
            entry("match", "Été à Montréal", width = 3840, height = 2160, hdr = true,
                metadata = BrowseMetadata(2023, listOf("Comédie")), watch = WatchState(positionMs = 3000)),
            entry("wrong-year", "Été à Montréal", width = 3840, height = 2160, hdr = true,
                metadata = BrowseMetadata(2024, listOf("Comédie")), watch = WatchState(positionMs = 3000)),
            entry("wrong-kind", "Été à Montréal", kind = "audio", width = 3840, height = 2160, hdr = true,
                metadata = BrowseMetadata(2023, listOf("Comédie")), watch = WatchState(positionMs = 3000)),
            entry("watched", "Été à Montréal", width = 3840, height = 2160, hdr = true,
                metadata = BrowseMetadata(2023, listOf("Comédie")), watch = WatchState(watched = true)),
        ))
        val query = LibraryQuery.fromParameters(mapOf("kind" to "movie", "q" to "ete a montreal", "year" to "2023",
            "watched" to "in_progress", "duration_max_minutes" to "90", "resolution" to "4k", "hdr_only" to "true"),
            mapOf("genre" to listOf("Action", "comedie")))
        assertEquals(listOf("match"), ids(catalog, query))
    }

    @Test fun `unknown durations and resolutions are excluded from technical filters`() {
        val catalog = LibraryBrowseCatalog("one", listOf(entry("unknown", duration = 0.0, width = null, height = null),
            entry("sd", duration = 600.0, width = 720, height = 480), entry("long", duration = 7200.0)))
        assertEquals(listOf("sd"), ids(catalog, query("duration_max_minutes" to "30")))
        assertEquals(listOf("sd"), ids(catalog, query("resolution" to "sd")))
        assertEquals(listOf("long"), ids(catalog, query("resolution" to "1080p")))
    }

    @Test fun `unwatched includes partially played media and watched remains distinct`() {
        val catalog = LibraryBrowseCatalog("one", listOf(entry("new"), entry("partial", watch = WatchState(positionMs = 100)),
            entry("done", watch = WatchState(watched = true))))
        assertEquals(listOf("new", "partial"), ids(catalog, query("watched" to "unwatched")))
        assertEquals(listOf("done"), ids(catalog, query("watched" to "watched")))
        assertEquals(listOf("partial"), ids(catalog, query("watched" to "in_progress")))
    }

    @Test fun `added sorting ignores a later file modification and retains deterministic pages`() {
        val catalog = LibraryBrowseCatalog("one", listOf(entry("a", title = "Same", added = 10, modified = 900),
            entry("b", title = "Same", added = 20, modified = 20), entry("c", title = "Same", added = 20, modified = 20)))
        val query = query("sort" to "added", "descending" to "true")
        val first = catalog.page(1, 0, null, query)
        val second = catalog.page(1, first.nextOffset!!, first.revision, query)
        val last = catalog.page(1, second.nextOffset!!, first.revision, query)
        assertEquals(listOf("b", "c", "a"), (first.items + second.items + last.items).map { it.media.id })
        assertEquals(3, first.total)
        assertNull(last.nextOffset)
        assertTrue(catalog.page(10, Int.MAX_VALUE, first.revision, query).items.isEmpty())
        assertThrows(CatalogRevisionChanged::class.java) { catalog.page(1, 1, "new-publication", query) }
    }

    @Test fun `metadata supplies genres and years while parsed year is fallback`() {
        val parsed = entry("parsed", metadata = BrowseMetadata()).let { it.copy(media = it.media.copy(parsedYear = 1999)) }
        val catalog = LibraryBrowseCatalog("one", listOf(parsed, entry("metadata", metadata = BrowseMetadata(2025, listOf("Science-fiction"))),
            entry("music", kind = "audio", metadata = BrowseMetadata())))
        assertEquals(listOf("parsed"), ids(catalog, query("year" to "1999")))
        val facets = catalog.facets("video")
        assertEquals(listOf("Science-fiction"), facets.genres)
        assertEquals(listOf(2025, 1999), facets.years)
        assertEquals(2, facets.total)
        assertEquals(2, catalog.counts["movie"])
        assertEquals(1, catalog.counts["audio"])
    }

    @Test fun `search covers folder artist album and series using accent insensitive text`() {
        val media = entry("a").let { it.copy(media = it.media.copy(artist = "Björk", album = "Début", folder = "Vidéos / Noël", seriesName = "Série")) }
        val catalog = LibraryBrowseCatalog("one", listOf(media))
        listOf("bjork", "debut", "noel", "serie").forEach { assertEquals(listOf("a"), ids(catalog, query("q" to it))) }
        assertEquals(listOf("a"), ids(catalog, query("q" to "noel bjork")))
        assertTrue(ids(catalog, query("q" to "noel missing")).isEmpty())
        val fromMetadata = LibraryBrowseCatalog("meta", listOf(entry("file-123", metadata = BrowseMetadata(title = "Le Voyage oublié"))))
        assertEquals(listOf("file-123"), ids(fromMetadata, query("q" to "oublie voyage")))
    }

    @Test fun `home includes whole recent and started series while excluding unrelated photos and music`() {
        val entries = (0 until 40).flatMap { series -> (1..2).map { episode ->
            entry("$series-$episode", kind = "series", added = series.toLong()).let {
                it.copy(media = it.media.copy(seriesKey = "show-$series"),
                    watch = if (series == 0 && episode == 1) WatchState(positionMs = 200, lastPlayedAtMs = 999) else WatchState())
            }
        } } + entry("photo", kind = "photo") + entry("audio", kind = "audio")
        val catalog = LibraryBrowseCatalog("one", entries)
        val homeIds = catalog.home().map { it.media.id }.toSet()
        assertTrue("0-1" in homeIds && "0-2" in homeIds)
        assertTrue("39-1" in homeIds && "39-2" in homeIds)
        assertFalse("1-1" in homeIds)
        assertFalse("photo" in homeIds || "audio" in homeIds)
        assertEquals(42, homeIds.size)
        assertEquals(80, catalog.counts["series"])
    }

    @Test fun `invalid inputs never silently broaden a filtered catalogue`() {
        listOf("kind" to "document", "watched" to "seen", "year" to "invalid", "year" to "0", "resolution" to "8k",
            "duration_max_minutes" to "NaN", "duration_max_minutes" to "-1", "hdr_only" to "yes",
            "sort" to "random", "descending" to "no").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { query(value) }
        }
    }

    @Test fun `large filtered library enumerates all matches once across small pages`() {
        val entries = (0 until 10_000).map { index -> entry(index.toString(), title = "Épisode ${index % 100}",
            added = index.toLong(), metadata = BrowseMetadata(2020 + index % 5, listOf(if (index % 2 == 0) "Action" else "Drame")),
            watch = if (index % 3 == 0) WatchState(watched = true) else WatchState()) }
        val catalog = LibraryBrowseCatalog("large", entries)
        val query = query("genre" to "Action", "year" to "2024", "watched" to "unwatched", "sort" to "added", "descending" to "true")
        val expected = entries.filter { it.media.id.toInt() % 2 == 0 && it.year == 2024 && !it.watch.watched }
            .sortedByDescending { it.media.addedAtMs }.map { it.media.id }
        val found = mutableListOf<String>()
        var offset = 0
        do {
            val page = catalog.page(17, offset, "large", query)
            assertEquals(expected.size, page.total)
            found += page.items.map { it.media.id }
            offset = page.nextOffset ?: -1
        } while (offset >= 0)
        assertEquals(expected, found)
        assertEquals(found.size, found.toSet().size)
    }

    @Test fun `fully watched older series do not evict started series from home`() {
        val entries = (0 until 40).flatMap { show -> (1..2).map { episode ->
            entry("$show-$episode", kind = "series", added = show.toLong()).let {
                it.copy(media = it.media.copy(seriesKey = "show-$show"), watch =
                    if (show == 0 && episode == 1) WatchState(positionMs = 300, lastPlayedAtMs = 1)
                    else if (show in 1..19) WatchState(watched = true, lastPlayedAtMs = 100 + show.toLong())
                    else WatchState())
            }
        } }
        val ids = LibraryBrowseCatalog("home", entries).home().map { it.media.id }.toSet()
        assertTrue("0-1" in ids && "0-2" in ids)
        assertFalse("1-1" in ids)
        assertEquals(42, ids.size)
    }
}
