package com.pluxy.tv.player

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.text.Normalizer

@JsonClass(generateAdapter = true)
data class MediaChapter(@Json(name = "start_ms") val startMs: Long,
    @Json(name = "end_ms") val endMs: Long, val title: String = "")

data class SkipSegment(val kind: String, val startMs: Long, val endMs: Long)

data class SkipMarkers(val introStartMs: Long = 0, val introEndMs: Long? = null,
    val creditsStartMs: Long? = null, val creditsEndMs: Long? = null) {
    fun segments(durationMs: Long): List<SkipSegment> = buildList {
        introEndMs?.let { if (introStartMs >= 0 && it > introStartMs && it < durationMs)
            add(SkipSegment("intro", introStartMs, it)) }
        creditsStartMs?.let { start ->
            val end = creditsEndMs ?: durationMs
            if (start >= 0 && end > start && end <= durationMs) add(SkipSegment("credits", start, end))
        }
    }
}

object SkipSegments {
    fun offer(segment: SkipSegment?): Boolean = segment != null &&
        (segment.kind != "intro" || segment.endMs - segment.startMs >= 8_000L)
    fun detected(chapters: List<MediaChapter>, durationMs: Long): List<SkipSegment> = chapters.take(300).mapNotNull { chapter ->
        val title = Normalizer.normalize(chapter.title, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
            .replace(Regex("^\\d+ "), "")
        val kind = when (title.replace(" ", "")) {
            "intro", "introduction", "opening", "openingcredits", "generiquedebut", "generiquededebut",
            "opencreditstart", "openingcreditstart", "openingcreditsstart", "introstart" -> "intro"
            "credits", "endcredits", "closingcredits", "ending", "outro", "generiquefin", "generiquedefin",
            "endcreditstart", "endcreditsstart", "creditsstart" -> "credits"
            else -> return@mapNotNull null
        }
        if (chapter.startMs < 0 || chapter.endMs <= chapter.startMs || chapter.endMs > durationMs) return@mapNotNull null
        if (kind == "intro" && (chapter.startMs > 900_000 || chapter.endMs > durationMs / 2 || chapter.endMs - chapter.startMs > 240_000)) return@mapNotNull null
        if (kind == "credits" && (chapter.startMs < durationMs / 2 || chapter.endMs - chapter.startMs > 1_200_000)) return@mapNotNull null
        SkipSegment(kind, chapter.startMs, chapter.endMs)
    }
    fun merge(detected: List<SkipSegment>, manual: List<SkipSegment>): List<SkipSegment> =
        manual + detected.filter { auto -> manual.none { it.kind == auto.kind } }
    fun resolve(detected: List<SkipSegment>, own: List<SkipSegment>, season: List<SkipSegment>): List<SkipSegment> {
        val precise = merge(detected, own)
        return precise + season.filter { fallback -> precise.none { it.kind == fallback.kind } }
    }
    fun active(segments: List<SkipSegment>, positionMs: Long): SkipSegment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 500 }
}
