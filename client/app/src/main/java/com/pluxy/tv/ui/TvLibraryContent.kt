package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import com.squareup.moshi.JsonClass

/** Organisation commune aux grilles, à l'accueil et aux sélections personnelles. */
object TvLibraryContent {
    @JsonClass(generateAdapter = false)
    data class Query(
        val destination: String,
        val search: String = "",
        val searchScope: String = "all",
        val watchedFilter: String = "all",
        val sort: String = "added",
        val photoFolder: String = "",
        val collapsedPhotoFolders: Set<String> = emptySet(),
        val advancedMode: Boolean = false,
        val genres: Set<String> = emptySet(),
        val year: Int? = null,
        val durationMaxMinutes: Int? = null,
        val resolution: String = "all",
        val hdrOnly: Boolean = false,
        val photoView: String = "folders",
        /** null : titres croissants, dates et nombres décroissants. */
        val descending: Boolean? = null,
        val collectionId: String = "",
        val titlePrefix: String = "",
    )

    fun homeShelves(
        items: List<MediaItem>,
        order: List<String> = DEFAULT_HOME_SHELF_ORDER,
        hidden: Set<String> = emptySet(),
    ): List<HomeShelf> {
        val recentSeries = seriesRows(items.filter { it.kind == "series" }).sortedByDescending { it.latestAdded }
        val shelves = buildList {
            HomeOrganizer.continueWatching(items).takeIf { it.isNotEmpty() }?.let { media ->
                add(HomeShelf("continue", "Reprendre", media.map { LibRow.QuickPlayRow(it, resumeSubtitle(it)) }))
            }
            HomeOrganizer.nextEpisodes(items).takeIf { it.isNotEmpty() }?.let { media ->
                add(HomeShelf("up_next", "À suivre", media.map {
                    LibRow.QuickPlayRow(it, listOfNotNull(it.seriesName, episodeNumber(it)).joinToString(" · "))
                }))
            }
            val recent = (HomeOrganizer.recentMovies(items).map { LibRow.MediaRow(it) } + recentSeries.take(12))
                .sortedByDescending(::rowAddedAt).take(16)
            if (recent.isNotEmpty()) add(HomeShelf("recent", "Ajouts récents", recent))
            val movies = items.filter { it.kind == "movie" }.sortedByDescending { it.addedAt }
                .take(36).map { LibRow.MediaRow(it) }
            if (movies.isNotEmpty()) add(HomeShelf("movies", "Films à découvrir", movies))
            if (recentSeries.isNotEmpty()) add(HomeShelf("series", "Séries à découvrir", recentSeries.take(36)))
        }.filterNot { it.id in hidden }
            .sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        return distinctShelves(shelves)
    }

    /** Les rails servent à choisir ; la grille [rows] conserve le catalogue complet. */
    fun destinationShelves(
        items: List<MediaItem>, destination: String, watchedFilter: String = "all", sort: String = "title",
    ): List<HomeShelf> = destinationShelves(items, Query(destination, watchedFilter = watchedFilter, sort = sort))

    fun destinationShelves(items: List<MediaItem>, query: Query): List<HomeShelf> {
        val all = rows(items, query.copy(search = ""))
        return when (query.destination) {
            "movie", "clip" -> {
                val movies = all.filterIsInstance<LibRow.MediaRow>().map { it.item }
                val resume = movies.filter { it.inProgress && !it.watched }.sortedByDescending { it.lastPlayedAt }
                distinctShelves(buildList {
                    if (resume.isNotEmpty()) add(HomeShelf("movies:continue", "Reprendre", resume.take(12).map {
                        LibRow.QuickPlayRow(it, resumeSubtitle(it))
                    }))
                    if (movies.isNotEmpty()) {
                        add(HomeShelf("movies:recent", "Ajoutés récemment", movies.sortedByDescending { it.addedAt }
                            .take(12).map { LibRow.MediaRow(it) }))
                        add(HomeShelf("movies:all", "À découvrir", movies.take(24).map { LibRow.MediaRow(it) }))
                    }
                })
            }
            "series" -> {
                val series = all.filterIsInstance<LibRow.SeriesRow>()
                val progress = series.filter { it.inProgressCount > 0 || it.watchedCount in 1 until it.count }
                    .sortedByDescending { it.latestPlayed }
                distinctShelves(buildList {
                    if (progress.isNotEmpty()) add(HomeShelf("series:continue", "À poursuivre", progress.take(12)))
                    if (series.isNotEmpty()) {
                        add(HomeShelf("series:recent", "Séries récentes", series.sortedByDescending { it.latestAdded }.take(12)))
                        add(HomeShelf("series:all", "À découvrir", series.take(24)))
                    }
                })
            }
            else -> emptyList()
        }
    }

    fun rows(items: List<MediaItem>, query: Query): List<LibRow> {
        if (query.search.isNotBlank()) return searchRows(items, query)
        return when (query.destination) {
            "movie" -> sortMedia(filterItems(items.filter { it.kind == "movie" }, query), query).map { LibRow.MediaRow(it) }
            "clip" -> sortMedia(filterItems(items.filter { it.kind == "clip" }, query), query).map { LibRow.MediaRow(it) }
            "series" -> matchingSeries(items, query).let { sortSeries(it, query) }
            "photo" -> photoRows(filterItems(items.filter { it.kind == "photo" }, query), query)
            "audio" -> if (query.photoView == "albums") albumRows(filterItems(items.filter { it.kind == "audio" }, query), "audio", query) else PhotoFolderTree.rows(filterItems(items.filter { it.kind == "audio" }, query), query.collapsedPhotoFolders, query)
            else -> emptyList()
        }
    }

    /** Filtres combinés : plusieurs genres sont un OU, les autres critères sont un ET. */
    fun filterItems(items: List<MediaItem>, query: Query): List<MediaItem> = items.filter { item ->
        (query.search.isNotBlank() || query.destination !in MEDIA_KINDS || item.kind == query.destination) &&
            matchesMetadata(item, query) && (query.search.isBlank() || LibraryOrganizer.matchesSearch(item, query.search)) &&
            (query.searchScope == "all" || item.kind == query.searchScope) &&
            (item.kind !in VIDEO_KINDS || matchesWatched(item, query.watchedFilter))
    }

    fun collectionItems(items: List<MediaItem>, collection: LibraryCollection): List<MediaItem> {
        val smart = collection.smartQuery
        return if (smart == null) items.filter { it.id in collection.mediaIds }
        else if (smart.destination == "series") {
            val keys = matchingSeries(items, smart).map { it.key }.toSet()
            items.filter { it.kind == "series" && mediaSeriesIdentity(it) in keys }
        } else filterItems(items.filter { smart.destination !in MEDIA_KINDS || it.kind == smart.destination }, smart)
    }

    fun collectionContentRows(items: List<MediaItem>, collection: LibraryCollection): List<LibRow> {
        val selected = collectionItems(items, collection)
        val order = collection.smartQuery ?: Query("all", sort = "title")
        return buildList {
            for ((kind, label) in listOf("movie" to "Films", "series" to "Séries", "clip" to "Vidéos", "audio" to "Musique", "photo" to "Photos")) {
                val media = selected.filter { it.kind == kind }
                val rows: List<LibRow> = when (kind) {
                    "series" -> sortSeries(seriesRows(media), order)
                    "audio" -> albumRows(media, "audio", order)
                    "photo" -> sortMedia(media, order.copy(sort = "captured")).map { LibRow.MediaRow(it) }
                    else -> sortMedia(media, order).map { LibRow.MediaRow(it) }
                }
                if (rows.isNotEmpty()) {
                    add(LibRow.Header("collection:${collection.id}:$kind", label))
                    addAll(rows)
                }
            }
        }
    }

    fun collectionRows(items: List<MediaItem>, collections: List<LibraryCollection>): List<LibRow.CollectionRow> =
        LibraryOrganizer.sortedBySearchKey(collections) { it.name }.map { collection ->
            val selected = collectionItems(items, collection)
            LibRow.CollectionRow(collection, selected, selected.firstNotNullOfOrNull { it.thumbnailUrl ?: it.posterUrl })
        }

    /** Conserve tous les épisodes d'une série dont les métadonnées correspondent. */
    private fun matchingSeries(items: List<MediaItem>, query: Query): List<LibRow.SeriesRow> {
        val episodes = items.filter { it.kind == "series" }
        val keys = episodes.filter { matchesMetadata(it, query) &&
            (query.search.isBlank() || LibraryOrganizer.matchesSearch(it, query.search)) }
            .map(::mediaSeriesIdentity).toSet()
        return seriesRows(episodes.filter { mediaSeriesIdentity(it) in keys }).filter { series ->
            when (query.watchedFilter) {
                "watched" -> series.count > 0 && series.watchedCount >= series.count
                "unwatched" -> series.watchedCount < series.count
                "in_progress" -> series.inProgressCount > 0 || series.watchedCount in 1 until series.count
                else -> true
            }
        }
    }

    internal fun seriesEpisodes(items: List<MediaItem>, key: String?, displayName: String): List<MediaItem> {
        val target = LibraryOrganizer.searchKey(key?.trim()?.takeIf { it.isNotEmpty() } ?: displayName)
        return items.filter { it.kind == "series" && mediaSeriesIdentity(it) == target }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
    }

    internal fun seriesRows(episodes: List<MediaItem>): List<LibRow.SeriesRow> = episodes.groupBy(::mediaSeriesIdentity)
        .map { (key, values) -> LibRow.SeriesRow(
            key = key,
            name = SeriesIdentity.displayName(values.first()),
            count = values.size,
            watchedCount = values.count { it.watched },
            poster = values.firstNotNullOfOrNull { it.posterUrl },
            thumbnail = values.firstNotNullOfOrNull { it.thumbnailUrl },
            latestAdded = values.maxOfOrNull { it.addedAt } ?: 0.0,
            inProgressCount = values.count { it.inProgress && !it.watched },
            latestPlayed = values.maxOfOrNull { it.lastPlayedAt } ?: 0.0,
            year = values.firstNotNullOfOrNull { it.year },
        ) }

    private fun searchRows(items: List<MediaItem>, query: Query): List<LibRow> = buildList {
        for ((kind, label) in listOf("movie" to "Films", "series" to "Séries", "clip" to "Vidéos", "photo" to "Photos", "audio" to "Musique")) {
            if (query.searchScope != "all" && query.searchScope != kind) continue
            val matching: List<LibRow> = if (kind == "series") matchingSeries(items, query).let { sortSeries(it, query) }
                else sortMedia(filterItems(items.filter { it.kind == kind }, query), query).map { LibRow.MediaRow(it) }
            if (matching.isNotEmpty()) {
                add(LibRow.Header("search:$kind", label))
                addAll(matching)
            }
        }
    }

    private fun photoRows(items: List<MediaItem>, query: Query): List<LibRow> {
        val selected = items.filter { query.photoFolder.isBlank() || photoFolderLabel(it) == query.photoFolder }
        if (query.photoView == "folders") return PhotoFolderTree.rows(selected, query.collapsedPhotoFolders, query)
        if (query.photoView != "timeline") return albumRows(selected, "photo", query)
        val grouped = selected.groupBy(LibraryOrganizer::photoMonthKey).entries
        val dated = grouped.filter { entry -> entry.value.any { LibraryOrganizer.captureTime(it) != null } }.sortedWith(
            compareByDescending<Map.Entry<String, List<MediaItem>>> { group ->
                group.value.maxOfOrNull { LibraryOrganizer.captureTime(it) ?: -1.0 } ?: -1.0
            },
        )
        val groups = (if (query.descending == false) dated.reversed() else dated) +
            grouped.filter { entry -> entry.value.all { LibraryOrganizer.captureTime(it) == null } }
        return buildList {
            groups.forEach { (key, photos) ->
                add(LibRow.Header(key, LibraryOrganizer.photoMonthLabel(photos.first())))
                addAll(sortMedia(photos, query.copy(sort = "captured")).map { LibRow.MediaRow(it) })
            }
        }
    }

    internal fun photoFolderLabel(item: MediaItem): String = LibraryOrganizer.photoAlbumLabel(item)

    fun albumRows(items: List<MediaItem>, kind: String, query: Query = Query(kind)): List<LibRow.AlbumRow> {
        val albums = items.filter { it.kind == kind }.groupBy {
            if (kind == "audio") LibraryOrganizer.audioAlbumKey(it) else LibraryOrganizer.photoAlbumKey(it)
        }.map { (key, media) ->
            val seed = media.first()
            val ordered = if (kind == "audio") LibraryOrganizer.albumTracks(media)
                else sortMedia(media, query.copy(sort = "captured"))
            val label = if (kind == "audio") seed.album?.trim()?.takeIf { it.isNotEmpty() }
                ?: LibraryOrganizer.audioAlbumLabel(seed) else LibraryOrganizer.photoAlbumLabel(seed)
            LibRow.AlbumRow(key, label,
                listOfNotNull(if (kind == "audio") seed.artist?.trim()?.takeIf { it.isNotEmpty() } else null,
                    "${media.size} ${if (kind == "audio") if (media.size > 1) "titres" else "titre" else if (media.size > 1) "photos" else "photo"}")
                    .joinToString(" · "),
                ordered, media.firstNotNullOfOrNull { it.thumbnailUrl }, media.maxOf { it.addedAt }, kind)
        }
        val sorted = if (query.sort == "title") LibraryOrganizer.sortedBySearchKey(albums) { it.name }
            else albums.sortedByDescending { it.latestAdded }
        return if (query.descending?.let { it != (query.sort != "title") } == true) sorted.reversed() else sorted
    }

    private fun matchesMetadata(item: MediaItem, query: Query): Boolean {
        if (query.titlePrefix.isNotBlank() && !LibraryOrganizer.searchKey(LibraryOrganizer.displayTitle(item))
                .startsWith(LibraryOrganizer.searchKey(query.titlePrefix))) return false
        if (query.genres.isNotEmpty() && item.genres.none { genre -> query.genres.any {
                LibraryOrganizer.searchKey(it) == LibraryOrganizer.searchKey(genre) } }) return false
        if (query.year != null && item.year != query.year) return false
        if (query.durationMaxMinutes != null && (item.duration <= 0 || item.duration > query.durationMaxMinutes * 60.0)) return false
        if (query.resolution != "all" && LibraryOrganizer.resolution(item) != query.resolution) return false
        if (query.hdrOnly && !item.isHdr) return false
        return true
    }

    private fun matchesWatched(item: MediaItem, filter: String): Boolean = when (filter) {
        "watched" -> item.watched
        "unwatched" -> !item.watched
        "in_progress" -> item.inProgress && !item.watched
        else -> true
    }

    internal fun sortMedia(items: List<MediaItem>, query: Query): List<MediaItem> {
        val sorted = when (query.sort) {
            "year" -> items.sortedByDescending { it.year ?: 0 }
            "duration" -> items.sortedByDescending { it.duration }
            "captured" -> items.sortedByDescending { LibraryOrganizer.captureTime(it) ?: -1.0 }
            "title" -> LibraryOrganizer.sortedBySearchKey(items, LibraryOrganizer::displayTitle)
            else -> items.sortedByDescending { it.addedAt }
        }
        return if (query.descending?.let { it != (query.sort != "title") } == true) sorted.reversed() else sorted
    }

    private fun sortSeries(items: List<LibRow.SeriesRow>, query: Query): List<LibRow.SeriesRow> {
        val sorted = when (query.sort) {
            "added" -> items.sortedByDescending { it.latestAdded }
            "year" -> items.sortedByDescending { it.year ?: 0 }
            else -> LibraryOrganizer.sortedBySearchKey(items) { it.name }
        }
        return if (query.descending?.let { it != (query.sort != "title") } == true) sorted.reversed() else sorted
    }

    private fun distinctShelves(shelves: List<HomeShelf>): List<HomeShelf> {
        val seen = mutableSetOf<String>()
        return shelves.mapNotNull { shelf ->
            val unique = shelf.rows.asSequence().filter { seen.add(discoveryIdentity(it)) }.take(20).toList()
            if (unique.isEmpty()) null else shelf.copy(rows = unique)
        }
    }

    private fun discoveryIdentity(row: LibRow): String = when (row) {
        is LibRow.MediaRow -> if (row.item.kind == "series") "series:${mediaSeriesIdentity(row.item)}" else "media:${row.item.id}"
        is LibRow.QuickPlayRow -> if (row.item.kind == "series") "series:${mediaSeriesIdentity(row.item)}" else "media:${row.item.id}"
        else -> row.stableKey
    }

    private fun rowAddedAt(row: LibRow): Double = when (row) {
        is LibRow.MediaRow -> row.item.addedAt
        is LibRow.QuickPlayRow -> row.item.addedAt
        is LibRow.SeriesRow -> row.latestAdded
        is LibRow.AlbumRow -> row.latestAdded
        is LibRow.CollectionRow -> row.items.maxOfOrNull { it.addedAt } ?: 0.0
        is LibRow.Header -> 0.0
    }

    private fun episodeNumber(item: MediaItem): String? = if (item.season != null && item.episode != null)
        episodeCode(item) else item.episode?.let { "Épisode $it" }

    private fun resumeSubtitle(item: MediaItem): String {
        val seconds = (item.duration * item.progress).toLong().coerceAtLeast(0)
        return "Reprendre à %02d:%02d".format(seconds / 60, seconds % 60)
    }

    private val DEFAULT_HOME_SHELF_ORDER = listOf("continue", "up_next", "recent", "movies", "series")
    private val VIDEO_KINDS = setOf("movie", "series", "clip")
    private val MEDIA_KINDS = VIDEO_KINDS + setOf("photo", "audio")
}
