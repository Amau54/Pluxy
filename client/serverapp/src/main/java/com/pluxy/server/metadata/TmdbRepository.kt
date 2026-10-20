package com.pluxy.server.metadata

import android.content.Context
import android.util.AtomicFile
import com.pluxy.server.ServerPreferences
import com.pluxy.server.ServerRuntime
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
    private val seasonStills = LinkedHashMap<String, Pair<Long, Map<Int, String>>>()
    internal val revision: Long get() = publication.get()
    @Volatile var status: String = ""
        private set
    private fun diagnostic(message: String) { status = message; ServerRuntime.update { it.copy(metadataStatus = message) } }
    private class LookupFailure(message: String) : IOException(message)
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
        releaseDate = json?.optStringOrNull("release_date"),
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
            val key = preferences.tmdbApiKey
            val existing = cached(item.id)
            if (existing != null && existing.optString("lookup_kind") == item.kind &&
                (existing.optBoolean("manual") || (!force && existing.optInt("lookup_revision") == LOOKUP_REVISION))) {
                val value = episodeArtwork(item, existing, key).apply { put("lookup_revision", LOOKUP_REVISION) }
                if (value.toString() != existing.toString()) save(item.id, value)
                return@withLock value
            }
            if (key.isBlank()) { diagnostic("Ajoutez votre clé TMDB pour obtenir les affiches et les synopsis."); return@withLock fallback(item) }
            val remote = try { fetch(item, key) } catch (error: IOException) {
                diagnostic(if (error is LookupFailure) error.message.orEmpty() else "Connexion à TMDB impossible. Vérifiez Internet et le VPN.")
                null
            } catch (_: org.json.JSONException) { diagnostic("Réponse TMDB illisible. Réessayez plus tard."); null }
            if (remote != null) {
                remote.put("lookup_revision", LOOKUP_REVISION)
                remote.put("lookup_kind", item.kind)
                val value = episodeArtwork(item, remote, key)
                save(item.id, value)
                diagnostic("Affiches et synopsis TMDB à jour.")
                value
            } else {
                fallback(item)
            }
        }
    }

    suspend fun enrichAll(items: List<MediaEntry>, force: Boolean = false) {
        if (!enriching.compareAndSet(false, true)) return
        try {
            if (preferences.tmdbApiKey.isBlank()) { diagnostic("Ajoutez votre clé TMDB pour obtenir les affiches et les synopsis."); return }
            if (force) requestMutex.withLock { seasonStills.clear() }
            val seriesMetadata = mutableMapOf<String, JSONObject>()
            var resolved = 0
            var issue: String? = null
            for (item in items.asSequence()
                .filter { it.kind == "movie" || it.kind == "series" }
            ) {
                val seriesKey = item.seriesKey?.takeIf { item.kind == "series" && it.isNotBlank() }
                val existing = cached(item.id)
                if (!force && existing != null && existing.optInt("lookup_revision") == LOOKUP_REVISION) {
                    val current = if (item.kind == "series") get(item) else existing
                    if (current.optBoolean("matched", false)) resolved++
                    seriesKey?.let { key ->
                        if (!seriesMetadata.containsKey(key)) seriesMetadata[key] = current
                    }
                    continue
                }
                val shared = seriesKey?.let { seriesMetadata[it] }
                if (shared != null) {
                    requestMutex.withLock { save(item.id, episodeArtwork(item, shared, preferences.tmdbApiKey)) }
                    resolved++
                    continue
                }
                runCatching { get(item, force) }.getOrNull()?.let { fetched ->
                    if (fetched.optBoolean("matched", false)) {
                        resolved++
                        seriesKey?.let { seriesMetadata[it] = fetched }
                    } else {
                        issue = status
                    }
                }
                if (status.startsWith("Clé TMDB refusée") || status.startsWith("TMDB reçoit trop") || status.startsWith("TMDB est temporairement")) break
                delay(180)
            }
            diagnostic(issue ?: if (resolved > 0) "$resolved médias avec affiches et synopsis TMDB." else "Aucun film ou série à enrichir. Vérifiez les catégories des dossiers.")
        } finally {
            enriching.set(false)
        }
    }

    suspend fun searchMatches(item: MediaEntry, query: String): JSONArray = withContext(Dispatchers.IO) {
        val key = preferences.tmdbApiKey
        require(key.isNotBlank()) { "Configurez une clé TMDB dans le serveur." }
        val type = if (item.kind == "series") "tv" else "movie"
        val results = getJson(apiUrl("search/$type", key).newBuilder().addQueryParameter("query", query)
            .addQueryParameter("language", "fr-FR").addQueryParameter("include_adult", "false").build(), key)?.optJSONArray("results") ?: JSONArray()
        JSONArray().apply { for (i in 0 until minOf(results.length(), 20)) {
            val row = results.optJSONObject(i) ?: continue
            put(JSONObject().put("id", row.getInt("id")).put("title", row.optString(if (type == "tv") "name" else "title"))
                .put("year", row.optString(if (type == "tv") "first_air_date" else "release_date").take(4)))
        } }
    }

    suspend fun chooseMatch(item: MediaEntry, id: Int, related: List<MediaEntry>): JSONObject = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val key = preferences.tmdbApiKey
            require(key.isNotBlank() && id > 0) { "Correspondance indisponible : vérifiez TMDB." }
            val value = fetch(item, key, id) ?: throw IOException("Correspondance TMDB indisponible")
            value.put("lookup_revision", LOOKUP_REVISION).put("lookup_kind", item.kind).put("manual", true)
            related.forEach { save(it.id, episodeArtwork(it, value, key)) }
            cached(item.id) ?: value
        }
    }

    private fun fetch(item: MediaEntry, key: String, selectedId: Int? = null): JSONObject? {
        val type = if (item.kind == "series") "tv" else "movie"
        val query = item.seriesName?.takeIf { it.isNotBlank() } ?: item.title
        val id = selectedId ?: run {
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
            } ?: run { diagnostic("Aucune correspondance TMDB pour « $query ». Vérifiez le titre et l’année du fichier."); return null }
            result.optInt("id", 0).takeIf { it > 0 } ?: return null
        }
        val detailsUrl = apiUrl("$type/$id", key).newBuilder()
            .addQueryParameter("language", "fr-FR")
            .addQueryParameter("append_to_response", "credits,videos")
            .build()
        val details = getJson(detailsUrl, key) ?: return null
        if (selectTrailer(details.optJSONObject("videos")?.optJSONArray("results")) == null) {
            val backup = runCatching { getJson(apiUrl("$type/$id/videos", key).newBuilder()
                .addQueryParameter("language", "en-US").addQueryParameter("include_video_language", "en,null").build(), key) }.getOrNull()
            if (backup != null) details.put("videos", backup)
        }
        return if (type == "tv") tvMetadata(details) else movieMetadata(details)
    }

    private fun episodeArtwork(item: MediaEntry, metadata: JSONObject, key: String): JSONObject {
        val value = JSONObject(metadata.toString())
        val id = value.optInt("tmdb_id", 0)
        val season = item.season; val episode = item.episode
        if (item.kind != "series" || !value.optBoolean("matched") || id <= 0 ||
            season == null || episode == null || season !in 0..1000 || episode !in 1..1000) return value.apply { remove("episode_still_url") }
        if (key.isBlank()) return value
        value.remove("episode_still_url")
        val cacheKey = "$id/$season:${key.hashCode()}"
        val now = System.currentTimeMillis()
        var cached = seasonStills[cacheKey]
        if (cached == null || cached.first <= now) {
            var ttl = 60_000L
            val stills = mutableMapOf<Int, String>()
            try {
                val details = getJson(apiUrl("tv/$id/season/$season", key).newBuilder().addQueryParameter("language", "fr-FR").build(), key)
                if (details != null) stills.putAll(TmdbMediaPolicy.stills(details))
                ttl = 86_400_000L
            } catch (_: IOException) { /* Les captures locales restent disponibles. */ }
            catch (_: org.json.JSONException) { /* Réponse temporairement inutilisable. */ }
            cached = Pair(now + ttl, stills)
            seasonStills[cacheKey] = cached
            while (seasonStills.size > 256) seasonStills.remove(seasonStills.keys.first())
        }
        value.putNullable("episode_still_url", cached.second[episode])
        return value
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
            putNullable("release_date", date.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) })
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

    private fun selectTrailer(videos: JSONArray?): String? = TmdbMediaPolicy.trailer(videos)

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
            if (!response.isSuccessful) throw LookupFailure(tmdbHttpMessage(response.code))
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
        private const val LOOKUP_REVISION = 3
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
