package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Règles communes de recherche et de regroupement de la médiathèque. */
object LibraryOrganizer {
    fun matchesSearch(item: MediaItem, query: String): Boolean {
        return matchesSearchKey(item, searchable(query))
    }

    internal fun matchesSearchKey(item: MediaItem, needle: String): Boolean {
        if (needle.isBlank()) return true
        val haystack = searchKey(listOfNotNull(item.title, item.metadataTitle, item.seriesName, item.artist, item.album, item.folder)
            .joinToString(" "))
        return needle.split(WHITESPACE).filter { it.isNotBlank() }.all(haystack::contains)
    }

    fun displayTitle(item: MediaItem): String = if (item.kind == "movie")
        item.metadataTitle?.trim()?.takeIf { it.isNotEmpty() } ?: item.title else item.title

    fun playbackTitle(item: MediaItem): String = when (item.kind) {
        "movie" -> item.metadataTitle?.trim()?.takeIf { it.isNotEmpty() } ?: item.title
        "series" -> listOfNotNull(
            item.metadataTitle?.trim()?.takeIf { it.isNotEmpty() } ?: SeriesIdentity.displayName(item),
            item.episode?.let { episodeCode(item) },
            cleanEpisodeTitle(item, 0).takeUnless { item.episode != null && it == "Épisode ${item.episode}" },
        ).filter { it.isNotBlank() }.joinToString(" · ")
        else -> item.title
    }

    /** Kotlin's sortedBy evaluates its selector for each comparison. Prepare expensive
     * Unicode keys once while retaining the stable order of equal normalized values. */
    internal fun <T> sortedBySearchKey(values: Iterable<T>, text: (T) -> String): List<T> =
        values.map { value -> value to searchKey(text(value)) }
            .sortedBy { it.second }
            .map { it.first }

    fun photoAlbumKey(item: MediaItem): String {
        item.folderId?.trim()?.takeIf { it.isNotEmpty() }?.let { return "source:$it" }
        return "folder:${item.folder?.trim()?.takeIf { it.isNotEmpty() } ?: "Dossier principal"}"
    }

    fun photoAlbumLabel(item: MediaItem): String {
        item.folder?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return "Dossier principal"
    }

    fun photoMonthKey(item: MediaItem): String = captureTime(item)?.let { "date:${monthKey(it)}" }
        ?: UNKNOWN_DATE_KEY

    fun photoMonthLabel(item: MediaItem): String = captureTime(item)?.let {
        SimpleDateFormat("MMMM yyyy", Locale.FRENCH).format(epochDate(it))
            .replaceFirstChar { it.uppercaseChar() }
    } ?: "Date de prise de vue inconnue"

    fun captureTime(item: MediaItem): Double? = item.capturedAt?.takeIf(::hasUsableDate)

    fun albumTracks(items: List<MediaItem>): List<MediaItem> = items.sortedWith(
        compareBy<MediaItem> { it.discNumber?.takeIf { number -> number > 0 } ?: 1 }
            .thenBy { it.trackNumber?.takeIf { number -> number > 0 }
                ?: TRACK_PREFIX.find(it.path.substringAfterLast('/').substringAfterLast('\\'))
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { number -> number > 0 } ?: Int.MAX_VALUE }
            .thenBy { searchKey(it.title) }
            .thenBy { it.id },
    )

    fun resolution(item: MediaItem): String? {
        return when (ArtworkPolicy.resolutionLabel(item.width, item.height)) {
            "4K" -> "4k"
            "1440P", "1080P" -> "1080p"
            "720P" -> "720p"
            null -> null
            else -> "sd"
        }
    }

    fun audioAlbumKey(item: MediaItem): String {
        val album = item.album?.trim()?.takeIf { it.isNotEmpty() }
        val artist = item.artist?.trim()?.takeIf { it.isNotEmpty() }
        if (album != null) return "album:${searchable(artist.orEmpty())}|${searchable(album)}"
        val folder = item.folder?.trim()?.takeIf { it.isNotEmpty() }
        if (folder != null) return "folder:${item.folderId ?: searchable(folder)}"
        if (artist != null) return "artist:${searchable(artist)}"
        return "untagged"
    }

    fun audioAlbumLabel(item: MediaItem): String {
        val album = item.album?.trim()?.takeIf { it.isNotEmpty() }
        val artist = item.artist?.trim()?.takeIf { it.isNotEmpty() }
        val folder = item.folder?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            album != null -> "${artist ?: "Artiste inconnu"} · $album"
            folder != null -> "Dossier · $folder"
            artist != null -> "$artist · Titres sans album"
            else -> "Titres sans album"
        }
    }

    fun sameAudioFolder(reference: MediaItem, candidate: MediaItem): Boolean =
        candidate.kind == "audio" && PhotoFolderTree.folderKey(reference) == PhotoFolderTree.folderKey(candidate)

    fun sameAudioAlbum(reference: MediaItem, candidate: MediaItem): Boolean =
        candidate.kind == "audio" && audioAlbumKey(reference) == audioAlbumKey(candidate)

    private fun monthKey(seconds: Double): String =
        SimpleDateFormat("yyyy-MM", Locale.ROOT).format(epochDate(seconds))

    private fun epochDate(seconds: Double): Date = Date((seconds * 1_000.0).toLong())

    private fun hasUsableDate(seconds: Double): Boolean =
        seconds.isFinite() && seconds in 1.0..MAX_EPOCH_SECONDS

    internal fun searchKey(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .replace("ß", "ss")
        .replace('ς', 'σ')

    private fun searchable(value: String): String = searchKey(value)

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")
    private val TRACK_PREFIX = Regex("^([0-9]{1,3})(?:[ ._-]|$)")
    private const val MAX_EPOCH_SECONDS = 4_102_444_800.0 // 2100-01-01 UTC
    private const val UNKNOWN_DATE_KEY = "date:unknown"
}
