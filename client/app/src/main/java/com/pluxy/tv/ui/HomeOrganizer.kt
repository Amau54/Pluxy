package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

/** Règles pures de l'accueil contextuel. Aucune dépendance Android : elles sont testables. */
object HomeOrganizer {
    private const val MAX_SHELF_ITEMS = 12

    /** Prefer the latest active episode, then the first unwatched one in episode order.
     * Completed series start again at their first episode; an empty series has no CTA. */
    fun resumeEpisode(episodes: List<MediaItem>): MediaItem? =
        episodes.asSequence()
            .filter { it.inProgress && !it.watched }
            .maxByOrNull { it.lastPlayedAt }
            ?: episodes.asSequence().filterNot { it.watched }.minWithOrNull(EPISODE_ORDER)
            ?: episodes.minWithOrNull(EPISODE_ORDER)

    fun continueWatching(items: List<MediaItem>): List<MediaItem> = items.asSequence()
        .filter { it.kind in VIDEO_KINDS && it.inProgress && !it.watched && it.progress >= 0.02 && it.progress < 0.90 }
        .sortedWith(
            compareByDescending<MediaItem> { it.lastPlayedAt }
                .thenByDescending { it.addedAt },
        )
        .take(MAX_SHELF_ITEMS)
        .toList()

    /**
     * Un seul épisode « à suivre » par série. Une série déjà présente dans Reprendre
     * n'est pas dupliquée. Une série jamais commencée reste dans « Séries récentes ».
     */
    fun nextEpisodes(items: List<MediaItem>): List<MediaItem> = items.asSequence()
        .filter { it.kind == "series" }
        .groupBy(::mediaSeriesIdentity)
        .values
        .mapNotNull { episodes ->
            if (episodes.any { it.inProgress && !it.watched }) return@mapNotNull null
            val ordered = episodes.sortedWith(EPISODE_ORDER)
            val lastWatched = ordered.indexOfLast { it.watched }
            if (lastWatched < 0) return@mapNotNull null
            ordered.drop(lastWatched + 1).firstOrNull { !it.watched }
                ?.let { next -> next to (ordered[lastWatched].lastPlayedAt) }
        }
        .sortedByDescending { (_, lastPlayed) -> lastPlayed }
        .map { it.first }
        .take(MAX_SHELF_ITEMS)
        .toList()

    fun recentMovies(items: List<MediaItem>): List<MediaItem> = items.asSequence()
        .filter { it.kind == "movie" }
        .sortedByDescending { it.addedAt }
        .take(MAX_SHELF_ITEMS)
        .toList()

    fun recentSeries(items: List<MediaItem>): List<List<MediaItem>> = items.asSequence()
        .filter { it.kind == "series" }
        .groupBy(::mediaSeriesIdentity)
        .values
        .sortedByDescending { episodes -> episodes.maxOfOrNull { it.addedAt } ?: 0.0 }
        .take(MAX_SHELF_ITEMS)

    fun hasContext(items: List<MediaItem>): Boolean =
        continueWatching(items).isNotEmpty() || nextEpisodes(items).isNotEmpty()

    private val EPISODE_ORDER = compareBy<MediaItem>(
        { it.season ?: Int.MAX_VALUE },
        { it.episode ?: Int.MAX_VALUE },
        { it.addedAt },
        { it.title.lowercase() },
    )

    private val VIDEO_KINDS = setOf("movie", "series", "clip")
}
