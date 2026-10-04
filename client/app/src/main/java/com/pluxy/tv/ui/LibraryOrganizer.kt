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
        return listOfNotNull(item.title, item.seriesName, item.artist, item.album, item.folder)
            .any { searchable(it).contains(needle) }
    }

    /** Kotlin's sortedBy evaluates its selector for each comparison. Prepare expensive
     * Unicode keys once while retaining the stable order of equal normalized values. */
    internal fun <T> sortedBySearchKey(values: Iterable<T>, text: (T) -> String): List<T> =
        values.map { value -> value to searchKey(text(value)) }
            .sortedBy { it.second }
            .map { it.first }

    fun photoAlbumKey(item: MediaItem): String {
        val folder = item.folder?.trim()?.takeIf { it.isNotEmpty() }
        if (folder != null) return "folder:${searchable(folder)}"
        if (!hasUsableDate(item.addedAt)) return UNKNOWN_DATE_KEY
        return "date:${monthKey(item.addedAt)}"
    }

    fun photoAlbumLabel(item: MediaItem): String {
        item.folder?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!hasUsableDate(item.addedAt)) return "Photos sans date"
        return SimpleDateFormat("MMMM yyyy", Locale.FRENCH)
            .format(epochDate(item.addedAt))
            .replaceFirstChar { it.uppercaseChar() }
    }

    fun audioAlbumKey(item: MediaItem): String {
        val album = item.album?.trim()?.takeIf { it.isNotEmpty() }
        val artist = item.artist?.trim()?.takeIf { it.isNotEmpty() }
        if (album != null) return "album:${searchable(artist.orEmpty())}|${searchable(album)}"
        val folder = item.folder?.trim()?.takeIf { it.isNotEmpty() }
        if (folder != null) return "folder:${searchable(folder)}"
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

    fun sameAudioAlbum(reference: MediaItem, candidate: MediaItem): Boolean =
        candidate.kind == "audio" && audioAlbumKey(reference) == audioAlbumKey(candidate)

    private fun monthKey(seconds: Double): String =
        SimpleDateFormat("yyyy-MM", Locale.ROOT).format(epochDate(seconds))

    private fun epochDate(seconds: Double): Date = Date((seconds * 1_000.0).toLong())

    private fun hasUsableDate(seconds: Double): Boolean =
        seconds.isFinite() && seconds in 1.0..MAX_EPOCH_SECONDS

    internal fun searchKey(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

    private fun searchable(value: String): String = searchKey(value)

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private const val MAX_EPOCH_SECONDS = 4_102_444_800.0 // 2100-01-01 UTC
    private const val UNKNOWN_DATE_KEY = "date:unknown"
}
