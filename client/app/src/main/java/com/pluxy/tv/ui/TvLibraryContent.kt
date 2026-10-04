package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

/** Construction pure des destinations TV : rails d'accueil et grilles spécialisées. */
object TvLibraryContent {
    data class Query(
        val destination: String,
        val search: String = "",
        /** Restriction volontaire de la recherche, utile à la télécommande. */
        val searchScope: String = "all",
        val watchedFilter: String = "all",
        val sort: String = "added",
        val photoFolder: String = "",
        val collapsedPhotoFolders: Set<String> = emptySet(),
        val advancedMode: Boolean = false,
    )

    fun homeShelves(
        items: List<MediaItem>,
        order: List<String> = DEFAULT_HOME_SHELF_ORDER,
        hidden: Set<String> = emptySet(),
    ): List<HomeShelf> = buildList {
        val recentSeries = seriesRows(items.filter { it.kind == "series" })
            .sortedByDescending { it.latestAdded }
        HomeOrganizer.continueWatching(items).takeIf { it.isNotEmpty() }?.let { media ->
            add(HomeShelf(
                id = "continue",
                title = "Reprendre",
                rows = media.map { LibRow.QuickPlayRow(it, resumeSubtitle(it)) },
            ))
        }
        HomeOrganizer.nextEpisodes(items).takeIf { it.isNotEmpty() }?.let { episodes ->
            add(HomeShelf(
                id = "up_next",
                title = "À suivre",
                rows = episodes.map {
                    LibRow.QuickPlayRow(
                        it,
                        listOfNotNull(it.seriesName, episodeNumber(it)).joinToString(" · "),
                    )
                },
            ))
        }

        val recentRows = buildList {
            addAll(HomeOrganizer.recentMovies(items).map { LibRow.MediaRow(it) })
            addAll(recentSeries.take(12))
        }.sortedByDescending(::rowAddedAt).take(16)
        if (recentRows.isNotEmpty()) {
            add(HomeShelf("recent", "Ajouts récents", recentRows))
        }

        val movies = items.asSequence()
            .filter { it.kind == "movie" }
            .sortedByDescending { it.addedAt }
            .take(20)
            .map { LibRow.MediaRow(it) }
            .toList()
        if (movies.isNotEmpty()) add(HomeShelf("movies", "Films", movies))

        val series = recentSeries.take(20)
        if (series.isNotEmpty()) add(HomeShelf("series", "Séries", series))
    }.filterNot { it.id in hidden }
        .sortedBy { shelf -> order.indexOf(shelf.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }

    /** Les destinations vidéo restent des rails : la grille exhaustive est réservée
     * à la recherche et aux médiathèques visuelles. */
    fun destinationShelves(
        items: List<MediaItem>,
        destination: String,
        watchedFilter: String = "all",
        sort: String = "title",
    ): List<HomeShelf> {
        val videos = items.filter { it.kind == destination }
        if (videos.isEmpty()) return emptyList()
        return when (destination) {
            "movie" -> buildList {
                val filtered = filterWatched(videos, watchedFilter)
                if (filtered.isEmpty()) return@buildList
                val resume = filtered.filter { it.inProgress && !it.watched }
                    .sortedByDescending { it.lastPlayedAt }
                if (resume.isNotEmpty()) add(HomeShelf("movies:continue", "Reprendre", resume.map { LibRow.QuickPlayRow(it, resumeSubtitle(it)) }))
                val new = filtered.sortedByDescending { it.addedAt }
                add(HomeShelf("movies:recent", "Ajoutés récemment", new.take(20).map { LibRow.MediaRow(it) }))
                add(HomeShelf("movies:all", "Tous les films", sortMedia(filtered, sort).map { LibRow.MediaRow(it) }))
            }
            "series" -> buildList {
                val all = filterSeriesWatched(seriesRows(videos), watchedFilter)
                if (all.isEmpty()) return@buildList
                val recent = all.sortedByDescending { it.latestAdded }
                add(HomeShelf("series:recent", "Séries récentes", recent.take(20)))
                val inProgress = recent.filter { it.watchedCount in 1 until it.count }
                if (inProgress.isNotEmpty()) add(HomeShelf("series:continue", "À poursuivre", inProgress))
                add(HomeShelf("series:all", "Toutes les séries", sortSeries(all, sort)))
            }
            else -> emptyList()
        }
    }

    fun rows(items: List<MediaItem>, query: Query): List<LibRow> {
        if (query.search.isNotBlank()) return searchRows(items, query)
        val watched = query.watchedFilter
        val sort = query.sort
        return when (query.destination) {
            "movie" -> filterWatched(items.filter { it.kind == "movie" }, watched)
                .let { sortMedia(it, sort) }
                .map { LibRow.MediaRow(it) }
            "series" -> filterSeriesWatched(
                seriesRows(items.filter { it.kind == "series" }),
                watched,
            ).let { sortSeries(it, sort) }
            "photo" -> photoRows(items, query.photoFolder, query.collapsedPhotoFolders, sort)
            "audio" -> audioRows(items, sort)
            else -> emptyList()
        }
    }

    /** Sélection robuste des épisodes, y compris avec un ancien serveur sans series_key. */
    internal fun seriesEpisodes(
        items: List<MediaItem>,
        key: String?,
        displayName: String,
    ): List<MediaItem> {
        val target = key?.trim()?.takeIf { it.isNotEmpty() }?.let(LibraryOrganizer::searchKey)
            ?: LibraryOrganizer.searchKey(displayName)
        return items.asSequence()
            .filter { it.kind == "series" && mediaSeriesIdentity(it) == target }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: 0 }))
            .toList()
    }

    internal fun seriesRows(episodes: List<MediaItem>): List<LibRow.SeriesRow> = episodes
        .groupBy(::mediaSeriesIdentity)
        .map { (key, values) ->
            val display = values.firstNotNullOfOrNull { it.seriesName?.ifBlank { null } }
                ?: values.first().title
            LibRow.SeriesRow(
                key = key,
                name = display,
                count = values.size,
                watchedCount = values.count { it.watched },
                poster = values.firstNotNullOfOrNull { it.posterUrl },
                thumbnail = values.firstNotNullOfOrNull { it.thumbnailUrl },
                latestAdded = values.maxOfOrNull { it.addedAt } ?: 0.0,
            )
        }

    private fun searchRows(items: List<MediaItem>, query: Query): List<LibRow> {
        val needle = LibraryOrganizer.searchKey(query.search)
        val matching = items.filter {
            (query.searchScope == "all" || it.kind == query.searchScope) &&
                LibraryOrganizer.matchesSearchKey(it, needle)
        }
        return buildList {
            filterWatched(matching.filter { it.kind == "movie" }, query.watchedFilter)
                .let { sortMedia(it, query.sort) }
                .takeIf { it.isNotEmpty() }
                ?.let { media ->
                    add(LibRow.Header("search:movies", "Films"))
                    addAll(media.map { LibRow.MediaRow(it) })
                }

            val seriesKeys = matching.asSequence()
                .filter { it.kind == "series" }
                .map(::mediaSeriesIdentity)
                .toSet()
            val matchingSeries = if (seriesKeys.isEmpty()) emptyList() else seriesRows(items.filter {
                it.kind == "series" && mediaSeriesIdentity(it) in seriesKeys
            })
            filterSeriesWatched(matchingSeries, query.watchedFilter)
                .let { sortSeries(it, query.sort) }
                .takeIf { it.isNotEmpty() }
                ?.let { series ->
                    add(LibRow.Header("search:series", "Séries"))
                    addAll(series)
                }

            matching.filter { it.kind == "photo" }
                .let { sortMedia(it, query.sort) }
                .takeIf { it.isNotEmpty() }
                ?.let { media ->
                    add(LibRow.Header("search:photos", "Photos"))
                    addAll(media.map { LibRow.MediaRow(it) })
                }

            matching.filter { it.kind == "audio" }
                .let { sortMedia(it, query.sort) }
                .takeIf { it.isNotEmpty() }
                ?.let { media ->
                    add(LibRow.Header("search:audio", "Musique"))
                    addAll(media.map { LibRow.MediaRow(it) })
                }
        }
    }

    /** Les images restent volontairement simples : toutes visibles par défaut ; le
     * dossier choisi dans l'en-tête agit comme un filtre, pas comme un tri forcé. */
    private fun photoRows(
        items: List<MediaItem>,
        selectedFolder: String,
        collapsedFolders: Set<String>,
        sort: String,
    ): List<LibRow> = buildList {
        val groups = items.asSequence()
            .filter { it.kind == "photo" }
            .filter { selectedFolder.isBlank() || photoFolderLabel(it) == selectedFolder }
            .groupBy(::photoFolderLabel)
        LibraryOrganizer.sortedBySearchKey(groups.entries) { it.key }.forEach { (folder, photos) ->
            val expanded = folder !in collapsedFolders
            add(LibRow.Header("photo:$folder", folder, collapsible = true, expanded = expanded))
            if (expanded) addAll(sortMedia(photos, sort).map { LibRow.MediaRow(it) })
        }
    }

    internal fun photoFolderLabel(item: MediaItem): String =
        item.folder?.trim()?.takeIf { it.isNotEmpty() } ?: "Dossier principal"

    private fun audioRows(items: List<MediaItem>, sort: String): List<LibRow> = buildList {
        val groups = items.filter { it.kind == "audio" }
            .groupBy(LibraryOrganizer::audioAlbumKey)
            .let { albums ->
                if (sort == "title") {
                    LibraryOrganizer.sortedBySearchKey(albums.entries) { (_, tracks) ->
                        LibraryOrganizer.audioAlbumLabel(tracks.first())
                    }
                } else {
                    albums.entries.sortedByDescending { (_, tracks) ->
                        tracks.maxOfOrNull { it.addedAt } ?: 0.0
                    }
                }
            }
        groups.forEach { (groupKey, tracks) ->
                add(LibRow.Header("audio:$groupKey", LibraryOrganizer.audioAlbumLabel(tracks.first())))
                val sorted = if (sort == "title") {
                    LibraryOrganizer.sortedBySearchKey(tracks) { it.title }
                } else {
                    tracks.sortedByDescending { it.addedAt }
                }
                addAll(
                    sorted.map { LibRow.MediaRow(it) },
                )
            }
    }

    private fun filterWatched(items: List<MediaItem>, watched: String): List<MediaItem> =
        when (watched) {
            "watched" -> items.filter { it.watched }
            "unwatched" -> items.filterNot { it.watched }
            else -> items
        }

    private fun filterSeriesWatched(
        series: List<LibRow.SeriesRow>,
        watched: String,
    ): List<LibRow.SeriesRow> = when (watched) {
        "watched" -> series.filter { it.count > 0 && it.watchedCount >= it.count }
        "unwatched" -> series.filter { it.watchedCount < it.count }
        else -> series
    }

    private fun sortMedia(items: List<MediaItem>, sort: String): List<MediaItem> = when (sort) {
        "year" -> items.sortedByDescending { it.year ?: 0 }
        "title" -> LibraryOrganizer.sortedBySearchKey(items) { it.title }
        else -> items.sortedByDescending { it.addedAt }
    }

    private fun sortSeries(items: List<LibRow.SeriesRow>, sort: String): List<LibRow.SeriesRow> =
        if (sort == "added") items.sortedByDescending { it.latestAdded }
        else LibraryOrganizer.sortedBySearchKey(items) { it.name }

    private fun rowAddedAt(row: LibRow): Double = when (row) {
        is LibRow.MediaRow -> row.item.addedAt
        is LibRow.QuickPlayRow -> row.item.addedAt
        is LibRow.SeriesRow -> row.latestAdded
        is LibRow.Header -> 0.0
    }

    private fun episodeNumber(item: MediaItem): String? =
        if (item.season != null && item.episode != null) {
            "S%02dE%02d".format(item.season, item.episode)
        } else {
            item.episode?.let { "Épisode $it" }
        }

    /** Même clé pour les serveurs récents (series_key) et les bibliothèques plus anciennes. */
    private fun mediaSeriesIdentity(item: MediaItem): String = LibraryOrganizer.searchKey(
        item.seriesKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.title,
    )

    private fun resumeSubtitle(item: MediaItem): String {
        val positionSeconds = (item.duration * item.progress).toLong().coerceAtLeast(0)
        val minutes = positionSeconds / 60
        val seconds = positionSeconds % 60
        return "Reprendre à %02d:%02d".format(minutes, seconds)
    }

    private val DEFAULT_HOME_SHELF_ORDER = listOf("continue", "up_next", "recent", "movies", "series")
}
