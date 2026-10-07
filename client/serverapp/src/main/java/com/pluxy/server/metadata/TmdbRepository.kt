package com.pluxy.server.metadata

import android.content.Context
import android.util.AtomicFile
import com.pluxy.server.ServerPreferences
import com.pluxy.server.library.MediaEntry
import com.pluxy.server.library.putNullable
import com.pluxy.server.library.BrowseMetadata
import com.pluxy.server.library.metadataGenres
import com.pluxy.server.library.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Enrichissement facultatif TMDB, mis en cache localement et jamais requis pour lire. */
class TmdbRepository(
    context: Context,
    private val preferences: ServerPreferences,
) {
    private val cacheDir = File(context.filesDir, "metadata").apply { mkdirs() }
    private val requestMutex = Mutex()
    private val enriching = AtomicBoolean(false)
    private val browseFacts = mutableMapOf<String, BrowseMetadata>()
    private val publication = AtomicLong()
    internal val revision: Long get() = publication.get()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Synchronized
    fun cached(itemId: String): JSONObject? {
        val file = cacheFile(itemId)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return runCatching { JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull()
    }

    /** Filter only stored metadata; no network calls and no repeated full JSON reads across pages. */
    @Synchronized
    internal fun facts(itemId: String): BrowseMetadata = browseFacts.getOrPut(itemId) {
        factsFromJson(cached(itemId))
    }

    private fun factsFromJson(json: JSONObject?): BrowseMetadata = BrowseMetadata(
        year = json?.optInt("year", 0)?.takeIf { it > 0 }, genres = metadataGenres(json),
        title = json?.takeIf { it.optBoolean("matched", false) }?.optStringOrNull("title"),
    )

    @Synchronized
    fun prune(validIds: Set<String>) {
        var changed = false
        cacheDir.listFiles()?.forEach { file ->
            val id = file.name.substringBeforeLast('.')
            if (id !in validIds && runCatching { file.delete() }.getOrDefault(false)) changed = true
        }
        synchronized(this) { browseFacts.keys.retainAll(validIds); if (changed) publication.incrementAndGet() }
    }

    suspend fun get(item: MediaEntry, force: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if (item.kind !in setOf("movie", "series")) return@withContext fallback(item)
        requestMutex.withLock {
            if (!force) cached(item.id)?.takeIf { it.optInt("lookup_revision") == 2 && it.optString("lookup_kind") == item.kind }
                ?.let { return@withLock it }
            val key = preferences.tmdbApiKey
            if (key.isBlank()) return@withLock fallback(item)
            val remote = runCatching { fetch(item, key) }.getOrNull()
            if (remote != null) {
                remote.put("lookup_revision", 2)
                remote.put("lookup_kind", item.kind)
                save(item.id, remote)
                remote
            } else {
                fallback(item)
            }
        }
    }

    suspend fun enrichAll(items: List<MediaEntry>, force: Boolean = false) {
        if (!enriching.compareAndSet(false, true)) return
        try {
            if (preferences.tmdbApiKey.isBlank()) return
            val seriesMetadata = mutableMapOf<String, JSONObject>()
            for (item in items.asSequence()
                .filter { it.kind == "movie" || it.kind == "series" }
                .take(MAX_ENRICH_ITEMS)
            ) {
                val seriesKey = item.seriesKey?.takeIf { item.kind == "series" && it.isNotBlank() }
                val existing = cached(item.id)
                if (!force && existing != null) {
                    seriesKey?.let { key ->
                        if (!seriesMetadata.containsKey(key)) seriesMetadata[key] = existing
                    }
                    continue
                }
                val shared = seriesKey?.let { seriesMetadata[it] }
                if (shared != null) {
                    save(item.id, shared)
                    continue
                }
                runCatching { get(item, force) }.getOrNull()?.let { fetched ->
                    if (fetched.optBoolean("matched", false)) {
                        seriesKey?.let { seriesMetadata[it] = fetched }
                    }
                }
                delay(180)
            }
        } finally {
            enriching.set(false)
        }
    }

    private fun fetch(item: MediaEntry, key: String): JSONObject? {
        val type = if (item.kind == "series") "tv" else "movie"
        val query = item.seriesName?.takeIf { it.isNotBlank() } ?: item.title
        val search = apiUrl("search/$type", key).newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("language", "fr-FR")
            .addQueryParameter("include_adult", "false")
            .apply {
                if (type == "movie") item.parsedYear?.let { addQueryParameter("year", it.toString()) }
            }
            .build()
        val results = getJson(search, key)?.optJSONArray("results") ?: return null
        val result = (0 until minOf(results.length(), 20)).mapNotNull { results.optJSONObject(it) }.firstOrNull {
            TitleMatch.matches(query, it.optString(if (type == "tv") "name" else "title"),
                it.optString(if (type == "tv") "original_name" else "original_title"))
        } ?: return null
        val id = result.optInt("id", 0).takeIf { it > 0 } ?: return null
        val detailsUrl = apiUrl("$type/$id", key).newBuilder()
            .addQueryParameter("language", "fr-FR")
            .addQueryParameter("append_to_response", "credits,videos")
            .build()
        val details = getJson(detailsUrl, key) ?: return null
        return if (type == "tv") tvMetadata(details) else movieMetadata(details)
    }

    private fun movieMetadata(details: JSONObject): JSONObject {
        val credits = details.optJSONObject("credits")
        val director = credits?.optJSONArray("crew")?.firstObject {
            it.optString("job").equals("Director", true)
        }?.optString("name")?.takeIf { it.isNotBlank() }
        return commonMetadata(
            details = details,
            title = details.optString("title"),
            originalTitle = details.optString("original_title"),
            date = details.optString("release_date"),
            runtime = details.optInt("runtime", 0).takeIf { it > 0 },
            director = director,
        )
    }

    private fun tvMetadata(details: JSONObject): JSONObject {
        val createdBy = details.optJSONArray("created_by")?.optJSONObject(0)
            ?.optString("name")?.takeIf { it.isNotBlank() }
        val runtimeArray = details.optJSONArray("episode_run_time")
        val runtime = runtimeArray?.optInt(0, 0)?.takeIf { it > 0 }
        return commonMetadata(
            details = details,
            title = details.optString("name"),
            originalTitle = details.optString("original_name"),
            date = details.optString("first_air_date"),
            runtime = runtime,
            director = createdBy,
        )
    }

    private fun commonMetadata(
        details: JSONObject,
        title: String,
        originalTitle: String,
        date: String,
        runtime: Int?,
        director: String?,
    ): JSONObject {
        val rating = details.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        val trailer = selectTrailer(details.optJSONObject("videos")?.optJSONArray("results"))
        val posterPath = details.optString("poster_path", "").takeIf { it.isNotBlank() }
        val backdropPath = details.optString("backdrop_path", "").takeIf { it.isNotBlank() }
        val year = date.take(4).toIntOrNull()
        return JSONObject().apply {
            put("tmdb_id", details.optInt("id"))
            put("title", title.ifBlank { originalTitle.ifBlank { "Média" } })
            putNullable("original_title", originalTitle.takeIf { it.isNotBlank() })
            putNullable("year", year)
            putNullable("overview", details.optString("overview").takeIf { it.isNotBlank() })
            putNullable("tagline", details.optString("tagline").takeIf { it.isNotBlank() })
            put("genres", JSONArray().apply {
                val genres = details.optJSONArray("genres")
                if (genres != null) for (i in 0 until genres.length()) {
                    genres.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { put(it) }
                }
            })
            putNullable("runtime", runtime)
            putNullable("rating", rating)
            put("ratings", JSONArray().apply {
                rating?.let {
                    put(JSONObject().apply {
                        put("source", "TMDB")
                        put("value", String.format(java.util.Locale.US, "%.1f/10", it))
                    })
                }
            })
            putNullable("poster_url", posterPath?.let { "https://image.tmdb.org/t/p/w500$it" })
            putNullable("backdrop_url", backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" })
            put("cast", JSONArray().apply {
                val cast = details.optJSONObject("credits")?.optJSONArray("cast")
                if (cast != null) for (i in 0 until minOf(cast.length(), 12)) {
                    val person = cast.optJSONObject(i) ?: continue
                    val profile = person.optString("profile_path", "").takeIf { it.isNotBlank() }
                    put(JSONObject().apply {
                        put("name", person.optString("name"))
                        putNullable("character", person.optString("character").takeIf { it.isNotBlank() })
                        putNullable("profile_url", profile?.let { "https://image.tmdb.org/t/p/w185$it" })
                    })
                }
            })
            putNullable("director", director)
            putNullable("trailer_youtube_key", trailer)
            putNullable("trailer_url", trailer?.let { "https://www.youtube.com/watch?v=$it" })
            put("matched", true)
            put("source", "tmdb")
        }
    }

    private fun selectTrailer(videos: JSONArray?): String? {
        if (videos == null) return null
        var fallback: String? = null
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            if (!video.optString("site").equals("YouTube", true)) continue
            val key = video.optString("key").takeIf { it.isNotBlank() } ?: continue
            if (fallback == null) fallback = key
            if (video.optString("type").equals("Trailer", true)) return key
        }
        return fallback
    }

    private fun fallback(item: MediaEntry): JSONObject = JSONObject().apply {
        put("tmdb_id", JSONObject.NULL)
        put("title", item.seriesName?.takeIf { item.kind == "series" } ?: item.title)
        put("original_title", JSONObject.NULL)
        putNullable("year", item.parsedYear)
        put("overview", JSONObject.NULL)
        put("tagline", JSONObject.NULL)
        put("genres", JSONArray())
        put("runtime", (item.durationSeconds / 60.0).toInt().takeIf { it > 0 } ?: JSONObject.NULL)
        put("rating", JSONObject.NULL)
        put("ratings", JSONArray())
        put("poster_url", JSONObject.NULL)
        put("backdrop_url", JSONObject.NULL)
        put("cast", JSONArray())
        put("director", JSONObject.NULL)
        put("trailer_youtube_key", JSONObject.NULL)
        put("trailer_url", JSONObject.NULL)
        put("matched", false)
        put("source", "filename")
    }

    private fun apiUrl(path: String, key: String): HttpUrl {
        val builder = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
        if (!key.startsWith("eyJ")) builder.addQueryParameter("api_key", key)
        return builder.build()
    }

    private fun getJson(url: HttpUrl, key: String): JSONObject? {
        val request = Request.Builder().url(url).apply {
            if (key.startsWith("eyJ")) header("Authorization", "Bearer $key")
            header("Accept", "application/json")
        }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_RESPONSE_BYTES) return null
            val output = ByteArrayOutputStream(64 * 1024)
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        }
    }

    @Synchronized
    private fun save(itemId: String, json: JSONObject) {
        val file = AtomicFile(cacheFile(itemId))
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(payload)
            output.fd.sync()
            file.finishWrite(output)
            // AtomicFile may log a rename failure without throwing: publish facts only after verification.
            if (!file.openRead().use { it.readBytes() }.contentEquals(payload)) throw IOException("Publication des métadonnées échouée")
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        synchronized(this) { browseFacts[itemId] = factsFromJson(json); publication.incrementAndGet() }
    }

    private fun cacheFile(itemId: String) = File(cacheDir, "$itemId.json")

    companion object {
        private const val MAX_ENRICH_ITEMS = 10_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

private inline fun JSONArray.firstObject(predicate: (JSONObject) -> Boolean): JSONObject? {
    for (i in 0 until length()) {
        val value = optJSONObject(i) ?: continue
        if (predicate(value)) return value
    }
    return null
}
