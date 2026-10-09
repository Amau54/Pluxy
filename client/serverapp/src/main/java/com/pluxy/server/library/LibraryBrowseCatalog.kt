package com.pluxy.server.library

import com.pluxy.server.WatchState
import java.text.Normalizer
import java.util.Locale

internal data class BrowseMetadata(val year: Int? = null, val genres: List<String> = emptyList(), val title: String? = null)
internal data class BrowseEntry(val media: MediaEntry, val metadata: BrowseMetadata, val watch: WatchState, val groupCount: Int = 1, val seasonCount: Int = 0) {
    val year: Int? get() = metadata.year ?: media.parsedYear
    val resolution: String? = if (media.kind in setOf("movie", "series", "clip")) mediaResolution(media.width, media.height) else null
    val sortTitle: String = normalizeLibraryText(metadata.title?.takeIf(String::isNotBlank) ?: media.title)
    val searchText: String = normalizeLibraryText(listOfNotNull(media.title, metadata.title, media.seriesName, media.artist, media.album, media.folder).joinToString(" "))
    val normalizedGenres: Set<String> = metadata.genres.map(::normalizeLibraryText).toSet()
}

internal data class LibraryQuery(
    val kind: String = "all",
    val query: String = "",
    val watched: String = "all",
    val genres: Set<String> = emptySet(),
    val year: Int? = null,
    val durationMaxMinutes: Double? = null,
    val resolution: String = "all",
    val hdrOnly: Boolean = false,
    val sort: String = "title",
    val descending: Boolean = false,
    val titlePrefix: String = "",
    val grouped: Boolean = false,
    val groupId: String = "",
) {
    val words: List<String> = query.split(whitespace).filter(String::isNotBlank)
    companion object {
        fun fromParameters(parameters: Map<String, String>, repeated: Map<String, List<String>> = emptyMap()): LibraryQuery {
            fun option(name: String, fallback: String, choices: Set<String>): String =
                parameters[name]?.trim()?.lowercase(Locale.ROOT)?.ifBlank { fallback }?.also {
                    require(it in choices) { "$name invalide" }
                } ?: fallback
            fun flag(name: String): Boolean = when (parameters[name]?.trim()?.lowercase(Locale.ROOT)) {
                null, "", "false", "0" -> false
                "true", "1" -> true
                else -> throw IllegalArgumentException("$name invalide")
            }
            val year = parameters["year"]?.takeIf { it.isNotBlank() }?.let {
                it.toIntOrNull()?.takeIf { value -> value in 1..9999 } ?: throw IllegalArgumentException("year invalide")
            }
            val duration = parameters["duration_max_minutes"]?.takeIf { it.isNotBlank() }?.let {
                it.toDoubleOrNull()?.takeIf { value -> value.isFinite() && value > 0 && value <= 100_000 }
                    ?: throw IllegalArgumentException("duration_max_minutes invalide")
            }
            val genres = (repeated["genre"] ?: listOfNotNull(parameters["genre"]))
                .map(String::trim).filter(String::isNotBlank)
            require(genres.size <= 32 && genres.all { it.length <= 100 }) { "genre invalide" }
            val query = parameters["q"].orEmpty().trim()
            require(query.length <= 300) { "q trop long" }
            val prefix = parameters["title_prefix"].orEmpty().lowercase(Locale.ROOT)
            require(prefix.isEmpty() || prefix.length == 1 && prefix[0] in 'a'..'z') { "title_prefix invalide" }
            return LibraryQuery(
                kind = option("kind", "all", setOf("all", "video", "movie", "series", "clip", "photo", "audio")),
                query = normalizeLibraryText(query),
                watched = option("watched", "all", setOf("all", "unwatched", "watched", "in_progress")),
                genres = genres.map(::normalizeLibraryText).toSet(), year = year, durationMaxMinutes = duration,
                resolution = option("resolution", "all", setOf("all", "4k", "1080p", "720p", "sd")),
                hdrOnly = flag("hdr_only"), sort = option("sort", "title", setOf("title", "added", "year", "duration")),
                descending = flag("descending"),
                titlePrefix = prefix,
                grouped = flag("grouped"),
                groupId = parameters["group_id"].orEmpty().also { require(it.length <= 128) { "group_id invalide" } },
            )
        }
    }
}

internal data class BrowsePage(val items: List<BrowseEntry>, val total: Int, val revision: String, val nextOffset: Int?)
internal data class LibraryFacets(val genres: List<String>, val years: List<Int>, val resolutions: List<String>, val total: Int)

/** One publication includes scan, metadata and watch state so filters stay stable across pages. */
internal class LibraryBrowseCatalog(val revision: String, entries: List<BrowseEntry>) {
    val entries = entries.toList()
    val counts: Map<String, Int> = listOf("movie", "series", "clip", "photo", "audio").associateWith { kind -> entries.count { it.media.kind == kind } }
    private val queryCache = linkedMapOf<LibraryQuery, List<BrowseEntry>>()

    @Synchronized
    fun page(limit: Int, offset: Int, requestedRevision: String?, query: LibraryQuery): BrowsePage {
        require(limit in 1..500 && offset >= 0) { "Pagination invalide" }
        if (requestedRevision != null && requestedRevision != revision) throw CatalogRevisionChanged()
        val seed = query.groupId.takeIf(String::isNotBlank)?.let { id -> entries.find { it.media.id == id } }
        val matches = queryCache[query] ?: entries.asSequence().filter { entry -> matches(entry, query) &&
            (query.groupId.isBlank() || seed != null && groupKey(entry.media) == groupKey(seed.media)) }
            .sortedWith(comparator(query)).toList().let { values ->
                if (!query.grouped) values else values.groupBy { groupKey(it.media) }.values.map { group ->
                    group.first().copy(groupCount = group.size, seasonCount = if (group.first().media.kind == "series") group.map { it.media.season ?: 0 }.distinct().size else 0)
                }
            }.also { result ->
                // Avoid retaining a catalogue-sized list for every search typed by a client.
                if (queryCache.size >= 8) queryCache.remove(queryCache.keys.first())
                queryCache[query] = result
            }
        val start = offset.coerceAtMost(matches.size)
        val end = (start.toLong() + limit).coerceAtMost(matches.size.toLong()).toInt()
        return BrowsePage(matches.subList(start, end), matches.size, revision, end.takeIf { it < matches.size })
    }

    private fun groupKey(item: MediaEntry): List<String> = when (item.kind) {
        "series" -> listOf(item.kind, item.seriesKey ?: normalizeLibraryText(item.seriesName ?: item.title))
        "photo" -> listOf(item.kind, item.sourceId ?: item.sourceName.orEmpty(), item.relativePath.orEmpty().replace('\\', '/').substringBeforeLast('/', ""))
        "audio" -> listOf(item.kind, normalizeLibraryText(item.artist.orEmpty()), normalizeLibraryText(item.album ?: item.sourceName.orEmpty()))
        else -> listOf(item.kind, item.id)
    }

    fun facets(kind: String): LibraryFacets {
        val selected = entries.filter { kindMatches(it.media.kind, kind) }
        return LibraryFacets(
            genres = selected.flatMap { it.metadata.genres }.distinct().sortedBy(::normalizeLibraryText),
            years = selected.mapNotNull { it.year }.distinct().sortedDescending(),
            resolutions = listOf("4k", "1080p", "720p", "sd").filter { value -> selected.any { it.resolution == value } },
            total = selected.size,
        )
    }

    fun home(): List<BrowseEntry> {
        val videos = entries.filter { it.media.kind in setOf("movie", "series", "clip") }
        val ids = linkedSetOf<String>()
        videos.filter { it.media.kind == "movie" }.sortedByDescending { it.media.addedAtMs }.take(20).forEach { ids += it.media.id }
        videos.filter { !it.watch.watched && it.watch.positionMs > 0 }.sortedByDescending { it.watch.lastPlayedAtMs }
            .take(12).forEach { ids += it.media.id }
        val series = videos.filter { it.media.kind == "series" }.groupBy { it.media.seriesKey ?: it.media.seriesName ?: it.media.id }
        val selectedGroups = linkedSetOf<String>()
        series.entries.sortedByDescending { it.value.maxOf { item -> item.media.addedAtMs } }.take(20).forEach { selectedGroups += it.key }
        series.entries.filter { (_, episodes) -> episodes.any { !it.watch.watched && it.watch.positionMs > 0 } ||
            episodes.any { it.watch.watched } && !episodes.all { it.watch.watched } }
            .sortedByDescending { it.value.maxOf { item -> item.watch.lastPlayedAtMs } }.take(12).forEach { selectedGroups += it.key }
        selectedGroups.forEach { key -> series.getValue(key).forEach { ids += it.media.id } }
        return entries.filter { it.media.id in ids }
    }

    private fun matches(entry: BrowseEntry, query: LibraryQuery): Boolean {
        if (query.titlePrefix.isNotEmpty() && !entry.sortTitle.startsWith(query.titlePrefix)) return false
        if (!kindMatches(entry.media.kind, query.kind) || query.query.isNotEmpty() &&
            query.words.any { !entry.searchText.contains(it) }) return false
        if (query.genres.isNotEmpty() && entry.normalizedGenres.none { it in query.genres }) return false
        if (query.year != null && entry.year != query.year) return false
        if (query.durationMaxMinutes != null && (entry.media.durationSeconds <= 0 || entry.media.durationSeconds > query.durationMaxMinutes * 60)) return false
        if (query.resolution != "all" && entry.resolution != query.resolution || query.hdrOnly && !entry.media.isHdr) return false
        return when (query.watched) {
            "watched" -> entry.watch.watched
            "unwatched" -> !entry.watch.watched
            "in_progress" -> !entry.watch.watched && entry.watch.positionMs > 0
            else -> true
        }
    }

    private fun comparator(query: LibraryQuery): Comparator<BrowseEntry> = Comparator { left, right ->
        val primary = when (query.sort) {
            "added" -> left.media.addedAtMs.compareTo(right.media.addedAtMs)
            "year" -> (left.year ?: 0).compareTo(right.year ?: 0)
            "duration" -> left.media.durationSeconds.compareTo(right.media.durationSeconds)
            else -> left.sortTitle.compareTo(right.sortTitle)
        }.let { if (query.descending) -it else it }
        if (primary != 0) primary else {
            val title = left.sortTitle.compareTo(right.sortTitle)
            if (title != 0) title else left.media.id.compareTo(right.media.id)
        }
    }
}

internal fun kindMatches(actual: String, selected: String): Boolean =
    selected == "all" || selected == actual || selected == "video" && actual in setOf("movie", "series", "clip")

internal fun normalizeLibraryText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD).replace(diacritics, "").lowercase(Locale.ROOT).replace("ß", "ss").replace('ς', 'σ')

private val diacritics = Regex("\\p{M}+")
private val whitespace = Regex("\\s+")

internal fun mediaResolution(width: Int?, height: Int?): String? {
    val knownWidth = width ?: 0
    val knownHeight = height ?: 0
    if (knownWidth <= 0 && knownHeight <= 0) return null
    return when {
        knownWidth >= 3840 || knownHeight >= 2160 -> "4k"
        knownWidth >= 1920 || knownHeight >= 1080 -> "1080p"
        knownWidth >= 1280 || knownHeight >= 720 -> "720p"
        else -> "sd"
    }
}
