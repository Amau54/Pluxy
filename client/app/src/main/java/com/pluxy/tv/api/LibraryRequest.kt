package com.pluxy.tv.api

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Contrat de recherche commun aux serveurs PC et Android. */
data class LibraryRequest(
    val kind: String = "all",
    val search: String = "",
    val watched: String = "all",
    val genres: Set<String> = emptySet(),
    val year: Int? = null,
    val durationMaxMinutes: Int? = null,
    val resolution: String = "all",
    val hdrOnly: Boolean = false,
    val sort: String = "title",
    val descending: Boolean? = null,
    val titlePrefix: String = "",
) {
    internal fun path(limit: Int, offset: Int, revision: String?): String {
        require(limit in 1..500 && offset >= 0)
        val url = "http://pluxy/api/library/items/page".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
        if (kind != "all") url.addQueryParameter("kind", kind)
        if (search.isNotBlank()) url.addQueryParameter("q", search)
        if (watched != "all") url.addQueryParameter("watched", watched)
        genres.sorted().forEach { url.addQueryParameter("genre", it) }
        year?.let { url.addQueryParameter("year", it.toString()) }
        durationMaxMinutes?.let { url.addQueryParameter("duration_max_minutes", it.toString()) }
        if (resolution != "all") url.addQueryParameter("resolution", resolution)
        if (hdrOnly) url.addQueryParameter("hdr_only", "true")
        if (titlePrefix.isNotBlank()) url.addQueryParameter("title_prefix", titlePrefix)
        url.addQueryParameter("sort", sort.takeIf { it in setOf("title", "added", "year", "duration") } ?: "added")
        url.addQueryParameter("descending", (descending ?: (sort != "title")).toString())
        revision?.let { url.addQueryParameter("revision", it) }
        return url.build().let { it.encodedPath + "?" + it.encodedQuery }
    }
}

data class LibraryFacets(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val resolutions: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    @com.squareup.moshi.Json(name = "query_supported") val querySupported: Boolean = false,
)

data class LibraryOverview(val items: List<MediaItem> = emptyList(), val counts: Map<String, Int> = emptyMap())
