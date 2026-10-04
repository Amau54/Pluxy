package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLibraryContentTest {
    @Test
    fun `accueil expose les rails reprendre a suivre recents films et series`() {
        val items = listOf(
            media("resume", progress = .4, inProgress = true, lastPlayed = 50.0),
            media("movie", addedAt = 40.0),
            episode("s1", 1, watched = true, lastPlayed = 30.0),
            episode("s2", 2, addedAt = 20.0),
        )

        val ids = TvLibraryContent.homeShelves(items).map { it.id }

        assertEquals(listOf("continue", "up_next", "recent", "movies", "series"), ids)
    }

    @Test
    fun `accueil respecte les rubriques masquees et l ordre personnel`() {
        val items = listOf(
            media("movie", addedAt = 40.0),
            episode("s1", 1),
        )

        val ids = TvLibraryContent.homeShelves(
            items,
            order = listOf("series", "movies", "recent", "continue", "up_next"),
            hidden = setOf("recent"),
        ).map { it.id }

        assertEquals(listOf("series", "movies"), ids)
    }

    @Test
    fun `recherche regroupe une serie sans dupliquer ses episodes`() {
        val items = listOf(
            episode("e1", 1, title = "Dark S01E01"),
            episode("e2", 2, title = "Dark S01E02"),
        )

        val rows = TvLibraryContent.rows(
            items,
            TvLibraryContent.Query(destination = "home", search = "dark"),
        )

        assertEquals(1, rows.filterIsInstance<LibRow.SeriesRow>().size)
        assertTrue(rows.first() is LibRow.Header)
    }

    @Test
    fun `recherche peut se limiter a un type de media`() {
        val items = listOf(
            media("film", kind = "movie", title = "Dune"),
            episode("serie", 1, title = "Dune Prophecy S01E01"),
            media("image", kind = "photo", title = "Dune coucher de soleil"),
        )

        val rows = TvLibraryContent.rows(
            items,
            TvLibraryContent.Query(destination = "home", search = "dune", searchScope = "movie"),
        )

        assertEquals(listOf("film"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        assertEquals(0, rows.filterIsInstance<LibRow.SeriesRow>().size)
    }

    @Test
    fun `identifiant stable ne depend pas de la reconstruction de liste`() {
        assertEquals(stableLongId("media:abc"), stableLongId("media:abc"))
        assertTrue(stableLongId("media:abc") != stableLongId("media:def"))
    }

    @Test
    fun `selection de serie accepte une cle absente ou entouree espaces`() {
        val items = listOf(
            episode("e1", 1, series = "Le Bureau", seriesKey = null),
            episode("e2", 2, series = "Le Bureau", seriesKey = "  le bureau  "),
        )
        val fallbackKey = TvLibraryContent.seriesRows(items).first().key

        val episodes = TvLibraryContent.seriesEpisodes(items, fallbackKey, "Le Bureau")

        assertEquals(listOf("e1", "e2"), episodes.map { it.id })
    }

    @Test
    fun `tri recent est applique aux photos et a la musique`() {
        val photos = listOf(
            media("old-photo", kind = "photo", addedAt = 1.0),
            media("new-photo", kind = "photo", addedAt = 2.0),
        )
        val audio = listOf(
            media("old-track", kind = "audio", addedAt = 1.0),
            media("new-track", kind = "audio", addedAt = 2.0),
        )

        val photoRows = TvLibraryContent.rows(
            photos,
            TvLibraryContent.Query(destination = "photo", sort = "added", advancedMode = true),
        )
        val audioRows = TvLibraryContent.rows(
            audio,
            TvLibraryContent.Query(destination = "audio", sort = "added", advancedMode = true),
        )

        assertEquals("new-photo", photoRows.filterIsInstance<LibRow.MediaRow>().first().item.id)
        assertEquals("new-track", audioRows.filterIsInstance<LibRow.MediaRow>().first().item.id)
    }

    @Test
    fun `ajouts recents trie les series avant de limiter a douze`() {
        val items = (0 until 13).map { index ->
            episode("e$index", 1, series = "Serie %02d".format(index), addedAt = index.toDouble())
        }

        val recent = TvLibraryContent.homeShelves(items).first { it.id == "recent" }
            .rows.filterIsInstance<LibRow.SeriesRow>()

        assertEquals(12, recent.size)
        assertEquals("Serie 12", recent.first().name)
        assertTrue(recent.none { it.name == "Serie 00" })
    }

    @Test
    fun `filtre films alimente tous les rails et tri demande le rail exhaustif`() {
        val watched = media("done", watched = true, addedAt = 30.0).copy(year = 2024)
        val old = media("old", addedAt = 20.0).copy(year = 2000)
        val resume = media("resume", addedAt = 10.0, inProgress = true, lastPlayed = 50.0)
            .copy(year = 2020)

        val shelves = TvLibraryContent.destinationShelves(
            listOf(watched, old, resume), "movie", watchedFilter = "unwatched", sort = "year",
        )

        assertEquals(listOf("resume"), shelfMediaIds(shelves, "movies:continue"))
        assertEquals(listOf("old", "resume"), shelfMediaIds(shelves, "movies:recent"))
        assertEquals(listOf("resume", "old"), shelfMediaIds(shelves, "movies:all"))
        assertTrue(shelves.flatMap { it.rows }.none {
            (it as? LibRow.MediaRow)?.item?.id == "done"
        })
    }

    @Test
    fun `filtre series considere la serie complete et conserve les comptes episodes`() {
        val items = listOf(
            episode("done1", 1, series = "Done", watched = true, addedAt = 30.0),
            episode("done2", 2, series = "Done", watched = true),
            episode("partial1", 1, series = "Partial", watched = true, addedAt = 20.0),
            episode("partial2", 2, series = "Partial"),
            episode("new1", 1, series = "New", addedAt = 10.0),
        )

        val watched = TvLibraryContent.destinationShelves(items, "series", "watched")
        val unwatched = TvLibraryContent.destinationShelves(items, "series", "unwatched", "added")

        assertEquals(listOf("Done"), watched.first { it.id == "series:all" }.rows
            .filterIsInstance<LibRow.SeriesRow>().map { it.name })
        assertTrue(watched.none { it.id == "series:continue" })
        val all = unwatched.first { it.id == "series:all" }.rows.filterIsInstance<LibRow.SeriesRow>()
        assertEquals(listOf("Partial", "New"), all.map { it.name })
        assertEquals(2, all.first().count)
        assertEquals(1, all.first().watchedCount)
        assertEquals(listOf("Partial"), unwatched.first { it.id == "series:continue" }.rows
            .filterIsInstance<LibRow.SeriesRow>().map { it.name })
    }

    @Test
    fun `rows applique filtre et tri sans mode avance`() {
        val items = listOf(
            media("done", title = "Alpha", watched = true),
            media("z", title = "Zulu"),
            media("e", title = "Éclair"),
        )

        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query(
            "movie", watchedFilter = "unwatched", sort = "title",
        ))

        assertEquals(listOf("e", "z"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test
    fun `recherche applique filtre et tri sans exclure photos ou musique non visionnees`() {
        val items = listOf(
            media("done", title = "Demo Alpha", watched = true),
            media("old", title = "Demo Zulu", addedAt = 10.0),
            media("new", title = "Demo Éclair", addedAt = 20.0),
            media("photo", kind = "photo", title = "Demo image"),
            media("audio", kind = "audio", title = "Demo chanson"),
        )

        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query(
            "movie", search = "demo", watchedFilter = "unwatched", sort = "added",
        ))

        assertEquals(listOf("new", "old", "photo", "audio"),
            rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test
    fun `dossiers photo aux noms equivalents ne perdent pas leurs images`() {
        val items = listOf(
            media("accent", kind = "photo").copy(folder = "Café"),
            media("plain", kind = "photo").copy(folder = "Cafe"),
        )

        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query("photo"))

        assertEquals(listOf("Café", "Cafe"), rows.filterIsInstance<LibRow.Header>().map { it.title })
        assertEquals(listOf("accent", "plain"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test
    fun `tri titre photos et musique fonctionne sans mode avance`() {
        for (kind in listOf("photo", "audio")) {
            val items = listOf(media("z", kind, "Zulu"), media("e", kind, "Éclair"))
            val rows = TvLibraryContent.rows(items, TvLibraryContent.Query(kind, sort = "title"))
            assertEquals(listOf("e", "z"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        }
    }

    @Test
    fun `filtre serie en recherche garde tous episodes de serie correspondante`() {
        val items = listOf(
            episode("partial1", 1, title = "Demo introduction", series = "Partial", watched = true),
            episode("partial2", 2, title = "Une autre aventure", series = "Partial"),
            episode("done1", 1, title = "Demo finale", series = "Done", watched = true),
        )

        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query(
            "series", search = "demo", searchScope = "series", watchedFilter = "unwatched", sort = "title",
        )).filterIsInstance<LibRow.SeriesRow>()

        assertEquals(listOf("Partial"), rows.map { it.name })
        assertEquals(2, rows.first().count)
        assertEquals(1, rows.first().watchedCount)
    }

    private fun shelfMediaIds(shelves: List<HomeShelf>, id: String): List<String> =
        shelves.first { it.id == id }.rows.mapNotNull {
            when (it) {
                is LibRow.MediaRow -> it.item.id
                is LibRow.QuickPlayRow -> it.item.id
                else -> null
            }
        }

    private fun episode(
        id: String,
        number: Int,
        title: String = "Demo S01E%02d".format(number),
        series: String = "Demo",
        seriesKey: String? = series,
        watched: Boolean = false,
        lastPlayed: Double = 0.0,
        addedAt: Double = 0.0,
    ) = media(
        id = id,
        kind = "series",
        title = title,
        series = series,
        seriesKey = seriesKey,
        episode = number,
        watched = watched,
        lastPlayed = lastPlayed,
        addedAt = addedAt,
    )

    private fun media(
        id: String,
        kind: String = "movie",
        title: String = id,
        series: String? = null,
        seriesKey: String? = series,
        episode: Int? = null,
        watched: Boolean = false,
        inProgress: Boolean = false,
        progress: Double = 0.0,
        lastPlayed: Double = 0.0,
        addedAt: Double = 0.0,
    ) = MediaItem(
        id = id,
        title = title,
        path = "",
        container = "mp4",
        size = 1,
        duration = 100.0,
        kind = kind,
        seriesName = series,
        seriesKey = seriesKey,
        season = if (kind == "series") 1 else null,
        episode = episode,
        watched = watched,
        inProgress = inProgress,
        progress = progress,
        lastPlayedAt = lastPlayed,
        addedAt = addedAt,
    )
}
