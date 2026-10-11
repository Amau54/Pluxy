package com.pluxy.server.library

import java.text.Normalizer

data class ParsedMediaName(
    val title: String,
    val year: Int?,
    val kind: String,
    val seriesName: String?,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
)

/** Parser sans dépendance, aligné sur les conventions Plex/Radarr du serveur PC. */
object FilenameParser {
    private val episodePattern = Regex(
        "(?i)(?:s(\\d{1,2})[ ._-]?e(\\d{1,3})|\\b(\\d{1,2})x(\\d{2,3})\\b)",
    )
    private val looseEpisodePattern = Regex("(?i)\\b(?:episode|épisode|ep)[ ._-]?(\\d{1,3})\\b")
    private val seasonFolderPattern = Regex("(?i)^(?:season|saison|s)[ ._-]?(\\d{1,3})$")
    private val seasonPackPattern = Regex("(?i)(?:^|[ ._-])(?:season|saison|s)[ ._-]?(\\d{1,3})(?=$|[ ._-])")
    private val seriesRoots = setOf("series", "série", "séries", "serie", "tv", "shows", "tvshows", "tv shows")
    private val yearPattern = Regex("(?<!\\d)(19\\d{2}|20[0-2]\\d)(?!\\d)")
    private val parenthesizedYear = Regex("[\\(\\[]\\s*(19\\d{2}|20[0-2]\\d)\\s*[\\)\\]]")
    private val strongTag = Regex(
        "(?i)\\b(?:2160p?|1440p|1080[pi]|720[pi]|4k|uhd|fhd|web[ ._-]?(?:dl|rip)|bluray|blu[ ._-]?ray|brrip|remux|hdtv|x[ ._-]?26[45]|h[ ._-]?26[45]|hevc|avc|av1)\\b",
    )
    private val cleanupTags = Regex(
        "(?i)\\b(?:2160p?|1440p|1080[pi]|720[pi]|4k|uhd|fhd|web[ ._-]?(?:dl|rip)|bluray|blu[ ._-]?ray|brrip|remux|hdtv|x[ ._-]?26[45]|h[ ._-]?26[45]|hevc|avc|av1|hdr10\\+?|hdr|dovi|dolby[ ._-]?vision|multi|truefrench|vff|vfq|vostfr|french|aac|ac3|eac3|dts(?:[ ._-]?hd)?|truehd|atmos|repack|proper|extended|director'?s[ ._-]?cut)\\b.*$",
    )
    private val leadingSite = Regex("(?i)^\\s*(?:www\\.)?[\\w-]+\\.(?:com|net|org|to|tv|info)\\s*-?\\s*")
    private val releaseGroup = Regex("(?i)-[a-z0-9]{2,}(?:\\[[a-z0-9.]+])?\\s*$")

    fun parse(fileName: String, ancestors: List<String> = emptyList()): ParsedMediaName {
        val stem = fileName.substringBeforeLast('.', fileName)
        val episodeMatch = episodePattern.find(stem)
        val looseEpisode = looseEpisodePattern.find(stem)
        val pathSuggestsSeries = ancestors.any { name ->
            name.lowercase() in seriesRoots || seasonPackPattern.containsMatchIn(name.trim())
        }
        val isSeries = episodeMatch != null || looseEpisode != null || pathSuggestsSeries

        if (isSeries) {
            val season = episodeMatch?.let { match ->
                (match.groups[1]?.value ?: match.groups[3]?.value)?.toIntOrNull()
            } ?: ancestors.asReversed().firstNotNullOfOrNull {
                seasonPackPattern.find(it.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            val episode = episodeMatch?.let { match ->
                (match.groups[2]?.value ?: match.groups[4]?.value)?.toIntOrNull()
            } ?: looseEpisode?.groupValues?.getOrNull(1)?.toIntOrNull()
            val parentName = ancestors.asReversed().firstOrNull {
                val normalized = it.lowercase().trim()
                normalized !in seriesRoots && !seasonFolderPattern.matches(it.trim())
            }
            val prefix = (episodeMatch ?: looseEpisode)?.let { stem.substring(0, it.range.first) }
            val display = cleanSeries(parentName.orEmpty()).ifBlank {
                cleanSeries(prefix.orEmpty()).ifBlank { cleanSeries(stem).ifBlank { "Série" } }
            }
            return ParsedMediaName(
                title = cleanDisplay(stem),
                year = null,
                kind = "series",
                seriesName = display,
                seriesKey = canonicalKey(display),
                season = season,
                episode = episode,
            )
        }

        var work = leadingSite.replace(stem, "")
        work = releaseGroup.replace(work, "")
        val spaced = work.replace(Regex("[._+\\[\\](){}]+"), " ")
        val parenthesized = parenthesizedYear.find(work)
        val strongIndex = strongTag.find(spaced)?.range?.first
        val yearMatches = yearPattern.findAll(spaced).toList()
        val selectedYear = when {
            parenthesized != null -> parenthesized.groupValues[1].toIntOrNull()
            strongIndex != null -> yearMatches.lastOrNull { it.range.first < strongIndex }?.value?.toIntOrNull()
            else -> yearMatches.lastOrNull { match ->
                spaced.substring(match.range.last + 1).trim(' ', '-', '.', '_').isNotBlank()
            }?.value?.toIntOrNull()
        }
        val cut = when {
            parenthesized != null -> parenthesized.range.first
            selectedYear != null -> yearMatches.lastOrNull { it.value == selectedYear.toString() }?.range?.first
            else -> strongIndex
        }
        var title = cleanDisplay(if (cut != null) spaced.substring(0, cut) else spaced)
        if (title.isBlank()) title = selectedYear?.toString() ?: cleanDisplay(spaced)
        val safeYear = if (title == selectedYear?.toString()) null else selectedYear
        return ParsedMediaName(title, safeYear, "movie", null, null, null, null)
    }

    private fun cleanSeries(value: String): String {
        val marker = listOfNotNull(seasonPackPattern.find(value), episodePattern.find(value))
            .minByOrNull { it.range.first }
        var cleaned = (if (marker != null && marker.range.first > 0) value.substring(0, marker.range.first) else value)
            .replace(Regex("[._]+"), " ")
        cleaned = cleanupTags.replace(cleaned, "")
        cleaned = cleaned.replace(
            Regex("(?i)[ ._-]+(?:season|saison|part|partie|volume|vol|s)\\s*\\d{1,3}\\s*$"),
            "",
        )
        return cleanDisplay(cleaned)
    }

    private fun cleanDisplay(value: String): String = value
        .replace(Regex("[._]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '_', '.', '·')

    private fun canonicalKey(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("^(?:le|la|les|l|the|a|an|un|une|des)[ '\\-]+"), "")
        return ascii.replace(Regex("[^a-z0-9]+"), "")
    }
}
