package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

/** Lignes réutilisées par les bibliothèques et les rails de l'accueil. */
sealed class LibRow {
    abstract val stableKey: String

    data class Header(
        val id: String,
        val title: String,
        /** Les dossiers Photos, eux seuls, se replient depuis leur titre. */
        val collapsible: Boolean = false,
        val expanded: Boolean = true,
        val depth: Int = 0,
    ) : LibRow() {
        override val stableKey: String = "header:$id"
    }

    data class MediaRow(val item: MediaItem) : LibRow() {
        override val stableKey: String = "media:${item.id}"
    }

    data class QuickPlayRow(val item: MediaItem, val subtitle: String) : LibRow() {
        override val stableKey: String = "quick:${item.id}"
    }

    data class SeriesRow(
        val key: String,
        val name: String,
        val count: Int,
        val watchedCount: Int,
        val poster: String?,
        val thumbnail: String?,
        val latestAdded: Double,
        val inProgressCount: Int = 0,
        val latestPlayed: Double = 0.0,
        val year: Int? = null,
    ) : LibRow() {
        override val stableKey: String = "series:$key"
    }

    data class AlbumRow(
        val key: String,
        val name: String,
        val subtitle: String,
        val items: List<MediaItem>,
        val thumbnail: String?,
        val latestAdded: Double,
        val kind: String,
    ) : LibRow() {
        override val stableKey: String = "album:$kind:$key"
    }

    data class CollectionRow(
        val collection: LibraryCollection,
        val items: List<MediaItem>,
        val thumbnail: String?,
    ) : LibRow() {
        override val stableKey: String = "collection:${collection.id}"
    }
}

/** Une sélection locale par identifiants, ou une sélection automatique par filtres. */
@com.squareup.moshi.JsonClass(generateAdapter = false)
data class LibraryCollection(
    val id: String,
    val name: String,
    val mediaIds: Set<String> = emptySet(),
    val smartQuery: TvLibraryContent.Query? = null,
)

data class HomeShelf(
    val id: String,
    val title: String,
    val rows: List<LibRow>,
)

data class LibraryFocusTarget(
    val shelfId: String?,
    val rowKey: String,
)

/** FNV-1a 64 bits : identifiants RecyclerView stables entre rafraîchissements. */
internal fun stableLongId(value: String): Long {
    var hash = -0x340d631b7bdddcdbL
    value.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}

/** Identité de série commune à la grille et à l'écran d'épisodes, anciens serveurs inclus. */
internal fun mediaSeriesIdentity(item: MediaItem): String = SeriesIdentity.key(item)
