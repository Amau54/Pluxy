package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

/** Repairs season-pack names from older servers without fuzzy-merging unrelated shows. */
object SeriesIdentity {
    private val season = Regex("(?i)(?:^|[ ._-])(?:saison|season|s)[ ._-]*\\d{1,3}(?=$|[ ._-])")
    private val episode = Regex("(?i)(?:^|[ ._-])(?:s\\d{1,3}[ ._-]?e\\d{1,3}|\\d{1,3}x\\d{2,3})(?=$|[ ._-])")

    fun displayName(item: MediaItem): String {
        val raw = item.seriesName?.takeIf(String::isNotBlank) ?: item.title
        val marker = season.find(raw) ?: episode.find(raw)
        return (if (marker != null && marker.range.first > 0) raw.substring(0, marker.range.first) else raw)
            .replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '·')
    }

    fun key(item: MediaItem): String {
        val raw = item.seriesName?.takeIf(String::isNotBlank) ?: item.title
        if (season.containsMatchIn(raw) || episode.containsMatchIn(raw)) {
            return LibraryOrganizer.searchKey(displayName(item))
                .replace(Regex("^(?:le|la|les|l|the|a|an|un|une|des)[ '\\-]+"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), "")
        }
        return item.seriesKey?.trim()?.takeIf(String::isNotEmpty)?.let(LibraryOrganizer::searchKey)
            ?: LibraryOrganizer.searchKey(raw)
    }
}
