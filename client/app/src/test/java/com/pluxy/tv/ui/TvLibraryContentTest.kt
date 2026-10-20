package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLibraryContentTest {
    @Test fun `release dates sort movies and series with unknown dates last and watched movies at the end`() {
        val films = listOf(media("older").copy(year = 2020), media("later").copy(year = 2024, releaseDate = "2024-12-01"),
            media("earlier").copy(year = 2024, releaseDate = "2024-01-20"), media("unknown"),
            media("watched").copy(year = 2025, watched = true))
        val query = TvLibraryContent.Query("movie", sort = "year")
        assertEquals(listOf("later", "earlier", "older", "unknown", "watched"),
            TvLibraryContent.sortMedia(films, query).map { it.id })
        assertEquals(listOf("older", "earlier", "later", "unknown", "watched"),
            TvLibraryContent.sortMedia(films, query.copy(descending = false)).map { it.id })
        val series = films.take(4).map { it.copy(kind = "series", seriesName = it.id, seriesKey = it.id) }
        assertEquals(listOf("later", "earlier", "older", "unknown"),
            TvLibraryContent.rows(series, query.copy(destination = "series")).filterIsInstance<LibRow.SeriesRow>().map { it.key })
    }
    @Test fun `premiere lettre suit le titre affiche sans accent`() {
        val items = listOf(media("a").copy(title = "Alpha", metadataTitle = "Évasion"),
            media("b").copy(title = "Été"), media("c").copy(title = "Alpha"))
        val query = TvLibraryContent.Query("movie", titlePrefix = "E", sort = "title")
        assertEquals(listOf("b", "a"), TvLibraryContent.rows(items, query).filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        assertEquals(listOf("c"), TvLibraryContent.filterItems(items, query.copy(titlePrefix = "A")).map { it.id })
    }
    @Test fun `bibliotheque exhaustive et decouverte sans doublons`() {
        val items = (1..80).map { media("film$it", addedAt = it.toDouble()) }
        val grid = TvLibraryContent.rows(items, TvLibraryContent.Query("movie"))
        val shelves = TvLibraryContent.destinationShelves(items, TvLibraryContent.Query("movie"))
        assertEquals(80, grid.size)
        assertTrue(shelves.all { it.rows.size <= 20 })
        val ids = shelves.flatMap { it.rows }.map { (it as LibRow.MediaRow).item.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.size < 80)
    }

    @Test fun `accueil court supprime les rubriques repetees`() {
        val shelves = TvLibraryContent.homeShelves(listOf(
            media("resume", inProgress = true, progress = .4, lastPlayedAt = 50.0),
            media("movie", addedAt = 40.0),
            episode("s1", 1).copy(watched = true, lastPlayedAt = 30.0), episode("s2", 2),
        ))
        assertEquals(listOf("continue", "up_next", "recent"), shelves.map { it.id })
        assertEquals(listOf("movie"), shelves.last().rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test fun `masquer recents restitue films et ordre personnel`() {
        val shelves = TvLibraryContent.homeShelves(listOf(media("movie"), episode("s1", 1)),
            order = listOf("series", "movies", "recent"), hidden = setOf("recent"))
        assertEquals(listOf("series", "movies"), shelves.map { it.id })
    }

    @Test fun `combinaison filtres genres ou et autres criteres et`() {
        val target = media("yes").copy(genres = listOf("Drame"), year = 2024, duration = 70.0 * 60,
            width = 3840, height = 1600, isHdr = true)
        val items = listOf(target, target.copy(id = "old", year = 2020), target.copy(id = "long", duration = 7200.0),
            target.copy(id = "sdr", isHdr = false), target.copy(id = "genre", genres = listOf("Comédie")),
            target.copy(id = "hd", width = 1920, height = 1080))
        val query = TvLibraryContent.Query("movie", genres = setOf("DRAMÉ", "Action"), year = 2024,
            durationMaxMinutes = 90, resolution = "4k", hdrOnly = true)
        assertEquals(listOf("yes"), TvLibraryContent.filterItems(items, query).map { it.id })
    }

    @Test fun `filtre en cours films exclut termines`() {
        val items = listOf(media("new"), media("started", inProgress = true),
            media("done", inProgress = true).copy(watched = true))
        assertEquals(listOf("started"), TvLibraryContent.rows(items,
            TvLibraryContent.Query("movie", watchedFilter = "in_progress")).filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test fun `premier episode partiel apparait dans a poursuivre`() {
        val first = episode("ep1", 1).copy(inProgress = true, progress = .35, lastPlayedAt = 120.0)
        val shelves = TvLibraryContent.destinationShelves(listOf(first, episode("ep2", 2)), "series")
        val series = shelves.first { it.id == "series:continue" }.rows.single() as LibRow.SeriesRow
        assertEquals(0, series.watchedCount)
        assertEquals(1, series.inProgressCount)
        assertEquals(2, series.count)
    }

    @Test fun `filtre series conserve comptes episodes complets`() {
        val items = listOf(episode("p1", 1, "Partial").copy(watched = true), episode("p2", 2, "Partial"),
            episode("done", 1, "Done").copy(watched = true))
        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query("series", watchedFilter = "unwatched"))
            .filterIsInstance<LibRow.SeriesRow>()
        assertEquals(listOf("Partial"), rows.map { it.name })
        assertEquals(2, rows.single().count)
        assertEquals(1, rows.single().watchedCount)
    }

    @Test fun `filtre metadonnees serie ne cache pas episodes non enrichis`() {
        val items = listOf(episode("ep1", 1).copy(genres = listOf("Drame"), year = 2020), episode("ep2", 2))
        val row = TvLibraryContent.rows(items, TvLibraryContent.Query("series", genres = setOf("Drame"), year = 2020)).single()
        assertEquals(2, (row as LibRow.SeriesRow).count)
    }

    @Test fun `recherche regroupe serie sans perdre autres episodes`() {
        val items = listOf(episode("ep1", 1).copy(title = "Introduction Demo", watched = true),
            episode("ep2", 2).copy(title = "Une autre aventure"))
        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query("home", search = "introduction",
            watchedFilter = "unwatched"))
        assertTrue(rows.first() is LibRow.Header)
        assertEquals(2, rows.filterIsInstance<LibRow.SeriesRow>().single().count)
    }

    @Test fun `recherche limitee au type et respecte etat`() {
        val items = listOf(media("yes").copy(title = "Dune"), media("done").copy(title = "Dune 2", watched = true),
            media("image", "photo").copy(title = "Dune image"))
        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query("home", search = "dune",
            searchScope = "movie", watchedFilter = "unwatched"))
        assertEquals(listOf("yes"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test fun `tri titres accents et sens inverse`() {
        val items = listOf(media("z").copy(title = "Zulu"), media("e").copy(title = "Éclair"))
        assertEquals(listOf("e", "z"), TvLibraryContent.rows(items, TvLibraryContent.Query("movie", sort = "title"))
            .filterIsInstance<LibRow.MediaRow>().map { it.item.id })
        assertEquals(listOf("z", "e"), TvLibraryContent.rows(items, TvLibraryContent.Query("movie", sort = "title", descending = true))
            .filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test fun `photo albums groupent sans fusionner sources homonymes`() {
        val items = listOf(media("p1", "photo").copy(folder = "Vacances", folderId = "diskA"),
            media("p2", "photo").copy(folder = "Vacances", folderId = "diskB"))
        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query("photo", photoView = "albums")).filterIsInstance<LibRow.AlbumRow>()
        assertEquals(2, rows.size)
        assertEquals(setOf("source:diskA", "source:diskB"), rows.map { it.key }.toSet())
        assertEquals(setOf("p1", "p2"), rows.flatMap { it.items }.map { it.id }.toSet())
    }

    @Test fun `photo albums legacy accents proches gardent identites distinctes`() {
        val items = listOf(media("accent", "photo").copy(folder = "Café"), media("plain", "photo").copy(folder = "Cafe"))
        assertEquals(2, TvLibraryContent.rows(items, TvLibraryContent.Query("photo", photoView = "albums")).size)
    }

    @Test fun `chronologie utilise capture et laisse date inconnue explicite`() {
        val captured = media("captured", "photo", addedAt = 1.0).copy(capturedAt = 1_735_776_000.0)
        val unknown = media("unknown", "photo", addedAt = 1_800_000_000.0)
        val rows = TvLibraryContent.rows(listOf(unknown, captured), TvLibraryContent.Query("photo", photoView = "timeline"))
        val headers = rows.filterIsInstance<LibRow.Header>()
        assertEquals("Date de prise de vue inconnue", headers.last().title)
        assertEquals(listOf("captured", "unknown"), rows.filterIsInstance<LibRow.MediaRow>().map { it.item.id })
    }

    @Test fun `musique presente albums puis pistes par disque et numero`() {
        val items = listOf(track("d2", 2, 1), track("d1t3", 1, 3), track("d1t1", 1, 1))
        val album = TvLibraryContent.rows(items, TvLibraryContent.Query("audio", photoView = "albums")).single() as LibRow.AlbumRow
        assertEquals("Album", album.name)
        assertEquals(listOf("d1t1", "d1t3", "d2"), album.items.map { it.id })
        assertEquals("audio", album.kind)
    }

    @Test fun `albums recents classes par derniere date ajout sans perdre pistes`() {
        val items = listOf(track("old", 1, 1).copy(album = "Old", addedAt = 1.0),
            track("new", 1, 1).copy(album = "New", addedAt = 2.0))
        val rows = TvLibraryContent.rows(items, TvLibraryContent.Query("audio", sort = "added", photoView = "albums"))
            .filterIsInstance<LibRow.AlbumRow>()
        assertEquals(listOf("New", "Old"), rows.map { it.name })
    }

    @Test fun `collection manuelle ignores identifiants disparus`() {
        val collection = LibraryCollection("favorites", "Favoris", setOf("a", "removed"))
        assertEquals(listOf("a"), TvLibraryContent.collectionItems(listOf(media("a"), media("b")), collection).map { it.id })
        assertEquals("collection:favorites", TvLibraryContent.collectionRows(listOf(media("a")), listOf(collection)).single().stableKey)
    }

    @Test fun `collection automatique reevaluer criteres lors ajout`() {
        val smart = LibraryCollection("short", "Courts non vus", smartQuery = TvLibraryContent.Query("movie",
            durationMaxMinutes = 90, watchedFilter = "unwatched"))
        val items = listOf(media("short").copy(duration = 1800.0), media("long").copy(duration = 8000.0),
            media("photo", "photo"), media("done").copy(watched = true))
        assertEquals(listOf("short"), TvLibraryContent.collectionItems(items, smart).map { it.id })
    }

    @Test fun `collection mixte affiche tous types et compte une serie une fois`() {
        val items = listOf(media("film"), episode("e1", 1), episode("e2", 2), track("track", 1, 1), media("image", "photo"))
        val collection = LibraryCollection("mix", "Sélection", items.map { it.id }.toSet())
        val rows = TvLibraryContent.collectionContentRows(items, collection)
        assertEquals(listOf("Films", "Séries", "Musique", "Photos"), rows.filterIsInstance<LibRow.Header>().map { it.title })
        assertEquals(2, rows.filterIsInstance<LibRow.SeriesRow>().single().count)
        assertEquals(1, rows.filterIsInstance<LibRow.AlbumRow>().single().items.size)
    }

    @Test fun `collection intelligente serie preserve tous episodes meme non vus`() {
        val items = listOf(episode("e1", 1).copy(watched = true), episode("e2", 2))
        val smart = LibraryCollection("started", "À finir", smartQuery = TvLibraryContent.Query("series", watchedFilter = "in_progress"))
        assertEquals(listOf("e1", "e2"), TvLibraryContent.collectionItems(items, smart).map { it.id })
        val row = TvLibraryContent.collectionContentRows(items, smart).filterIsInstance<LibRow.SeriesRow>().single()
        assertEquals(2, row.count)
        assertEquals(1, row.watchedCount)
    }

    @Test fun `collection query et nouveaux metadata conservent json`() {
        val moshi = Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        val collection = LibraryCollection("smart", "HDR récent", smartQuery = TvLibraryContent.Query("movie",
            genres = setOf("Drame"), year = 2024, hdrOnly = true, descending = false))
        val adapter = moshi.adapter(LibraryCollection::class.java)
        assertEquals(collection, adapter.fromJson(adapter.toJson(collection)))
        val media = media("photo", "photo").copy(capturedAt = 123.0, modifiedAt = 456.0,
            genres = listOf("Drame"), folderId = "source", relativePath = "Photos/a.jpg")
        val mediaAdapter = moshi.adapter(MediaItem::class.java)
        assertEquals(media, mediaAdapter.fromJson(mediaAdapter.toJson(media)))
    }

    @Test fun `selection serie fallback support espaces et absence cle`() {
        val items = listOf(episode("ep1", 1).copy(seriesKey = null), episode("ep2", 2).copy(seriesKey = "  Demo  "))
        assertEquals(listOf("ep1", "ep2"), TvLibraryContent.seriesEpisodes(items, " demo ", "Demo").map { it.id })
    }

    @Test fun `identifiants stables`() {
        assertEquals(stableLongId("media:abc"), stableLongId("media:abc"))
        assertFalse(stableLongId("media:abc") == stableLongId("media:def"))
    }

    private fun episode(id: String, episode: Int, series: String = "Demo") = media(id, "series").copy(
        seriesName = series, seriesKey = series, season = 1, episode = episode,
    )
    private fun track(id: String, disc: Int, number: Int) = media(id, "audio").copy(
        album = "Album", artist = "Artiste", discNumber = disc, trackNumber = number,
    )
    private fun media(id: String, kind: String = "movie", addedAt: Double = 0.0, inProgress: Boolean = false,
        progress: Double = 0.0, lastPlayedAt: Double = 0.0) = MediaItem(
        id, id, "", "mp4", 1, 100.0, kind = kind, addedAt = addedAt,
        inProgress = inProgress, progress = progress, lastPlayedAt = lastPlayedAt,
    )
}
