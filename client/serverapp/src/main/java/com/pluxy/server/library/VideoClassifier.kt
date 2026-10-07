package com.pluxy.server.library

import java.text.Normalizer
import java.util.Locale

/** Le type choisi prime ; sans indice fiable, on conserve une simple vidéo. */
object VideoClassifier {
    private fun fold(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[._-]+"), " ")
    private val camera = Regex("^(?:VID|MOV|PXL|IMG|GOPR|GH\\d|GX\\d|DJI|DSC)[ ._-]?\\d|^\\d{8}[ _-]\\d{6}", RegexOption.IGNORE_CASE)
    private val personal = Regex("\\b(?:dcim|camera|famille|personnel|vacances|souvenirs|gopro)\\b")
    private val year = Regex("\\b(?:19|20)\\d{2}\\b")
    private val release = Regex("\\b(?:1080p|2160p|720p|bluray|blu ray|bdrip|webrip|web dl|dvdrip|x264|x265|h264|h265)\\b")

    fun parse(name: String, folders: List<String>, category: String = "auto", duration: Double = 0.0,
              embeddedTitle: String? = null): ParsedMediaName {
        val parts = folders.map(::fold)
        val parsed = FilenameParser.parse(name, if (category == "series") folders + "Séries" else folders)
        val text = fold(name)
        val kind = when {
            category in setOf("movie", "series", "clip") -> category
            camera.containsMatchIn(name) || parts.any(personal::containsMatchIn) -> "clip"
            parsed.kind == "series" -> "series"
            parts.any { it.trim() in setOf("films", "film", "movies", "movie", "cinema") } -> "movie"
            year.containsMatchIn(text) && release.containsMatchIn(text) -> "movie"
            duration >= 45 * 60 && (year.containsMatchIn(text) || !embeddedTitle.isNullOrBlank()) -> "movie"
            else -> "clip"
        }
        return if (kind == "series") parsed.copy(kind = kind) else parsed.copy(kind = kind,
            seriesName = null, seriesKey = null, season = null, episode = null,
            year = parsed.year.takeIf { kind == "movie" })
    }
}
