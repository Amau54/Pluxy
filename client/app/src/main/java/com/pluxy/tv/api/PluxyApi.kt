package com.pluxy.tv.api

import com.pluxy.tv.PluxyApplication
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Erreur réseau lisible (statut HTTP + détail) — évite les messages opaques. */
class ApiException(val code: Int, message: String) : IOException(message)

/** Client réseau Pluxy (OkHttp + Moshi). Toutes les méthodes sont suspend. */
class PluxyApi(val baseUrl: String, val sessionId: String = UUID.randomUUID().toString()) {

    init { require(sessionId.matches(Regex("[A-Za-z0-9_-]{8,64}"))) }

    private val serverHost = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
    private val sameServerOnly = Interceptor { chain ->
        if (!LanUrlPolicy.isSameServer(baseUrl, chain.request().url.toString())) {
            throw IOException("Requête hors du serveur Pluxy sélectionné")
        }
        chain.proceed(chain.request().newBuilder().header("X-Pluxy-Session", sessionId).build())
    }

    private val http = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String) =
                if (!hostname.equals(serverHost, ignoreCase = true)) {
                    throw UnknownHostException("Hôte différent du serveur Pluxy sélectionné")
                } else {
                    LanUrlPolicy.resolveTrustedHost(hostname)
                }
            }
        )
        // L'intercepteur applicatif couvre les URL HLS ; le réseau couvre aussi les
        // éventuelles redirections suivies en interne par OkHttp.
        .addInterceptor(sameServerOnly)
        .addNetworkInterceptor(sameServerOnly)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonMedia = "application/json".toMediaType()

    fun abs(relative: String): String {
        val resolved = LanUrlPolicy.resolveSameServer(baseUrl, relative)
        val url = resolved.toHttpUrl()
        return if (url.encodedPath.startsWith("/stream/hls/")) {
            url.newBuilder().setQueryParameter("session", sessionId).build().toString()
        } else resolved
    }

    /** Used by isolated background senders when their session changes. */
    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    // --- Helpers --------------------------------------------------------- //
    private fun Response.bodyOrThrow(maxBytes: Int = MAX_JSON_BYTES): String {
        if (!isSuccessful) {
            throw ApiException(code, "Serveur: HTTP $code ${message}".trim())
        }
        val responseBody = body ?: return ""
        val announced = responseBody.contentLength()
        if (announced > maxBytes) throw ApiException(413, "Réponse serveur trop volumineuse")
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        responseBody.byteStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw ApiException(413, "Réponse serveur trop volumineuse")
                out.write(buffer, 0, count)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private suspend inline fun <reified T> getJson(path: String): T = http
        .newCall(Request.Builder().url("$baseUrl$path").build())
        .awaitResult { resp ->
            val body = resp.bodyOrThrow()
            moshi.adapter(T::class.java).fromJson(body)
                ?: throw ApiException(resp.code, "Réponse vide pour $path")
        }

    // --- Endpoints ------------------------------------------------------- //
    suspend fun listItems(query: LibraryRequest = LibraryRequest()): List<MediaItem> = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            try {
                return@withContext collectLibraryPages { offset, revision ->
                    getJson<LibraryPage>(query.path(500, offset, revision))
                }
            } catch (error: ApiException) {
                if (error.code in setOf(404, 405)) return@withContext legacyListItems()
                if (error.code != 409 || attempt == 1) throw error
            }
        }
        throw ApiException(409, "La bibliothèque a changé pendant le chargement")
    }

    suspend fun libraryPage(query: LibraryRequest, offset: Int = 0, revision: String? = null): LibraryPage =
        withContext(Dispatchers.IO) { getJson(query.path(100, offset, revision)) }

    suspend fun libraryFacets(kind: String = "all"): LibraryFacets = withContext(Dispatchers.IO) {
        val suffix = if (kind == "all") "" else "?kind=" + java.net.URLEncoder.encode(kind, "UTF-8")
        getJson("/api/library/facets$suffix")
    }

    suspend fun libraryOverview(): LibraryOverview = withContext(Dispatchers.IO) {
        getJson("/api/library/home")
    }

    private suspend fun legacyListItems(): List<MediaItem> =
        http.newCall(Request.Builder().url("$baseUrl/api/library/items").build())
            .awaitResult { resp ->
                val body = resp.bodyOrThrow()
                val type = Types.newParameterizedType(List::class.java, MediaItem::class.java)
                moshi.adapter<List<MediaItem>>(type).fromJson(body) ?: emptyList()
            }

    suspend fun rescan(): Unit = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url("$baseUrl/api/library/scan")
                .post("".toRequestBody(jsonMedia)).build()
        ).awaitResult { it.bodyOrThrow() }
    }

    /** Relance l'enrichissement TMDB/OMDb (affiches, synopsis, notes) en arrière-plan. */
    suspend fun refreshMetadata(): Unit = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url("$baseUrl/api/library/refresh-metadata")
                .post("".toRequestBody(jsonMedia)).build()
        ).awaitResult { it.bodyOrThrow() }
    }

    suspend fun scanStatus(): ScanStatus =
        withContext(Dispatchers.IO) { getJson("/api/library/scan-status") }

    suspend fun clientRuntime(): ClientRuntime =
        withContext(Dispatchers.IO) { getJson("/api/settings/client") }

    suspend fun metadata(itemId: String, refresh: Boolean = false): MovieMetadata? =
        withContext(Dispatchers.IO) {
            networkOrNull {
                getJson<MovieMetadata>(
                    "/api/library/items/$itemId/metadata" + if (refresh) "?refresh=true" else ""
                )
            }
        }

    suspend fun serverInfo(): ServerInfo? =
        withContext(Dispatchers.IO) { networkOrNull { getJson<ServerInfo>("/api/server/info") } }

    @OptIn(UnstableApi::class)
    suspend fun decide(itemId: String): PlaybackDecision = withContext(Dispatchers.IO) {
        requestDecision("/api/playback/decide", itemId)
    }

    suspend fun diagnose(itemId: String): PlaybackDecision = withContext(Dispatchers.IO) {
        requestDecision("/api/playback/diagnose", itemId)
    }

    @OptIn(UnstableApi::class)
    private suspend fun requestDecision(path: String, itemId: String): PlaybackDecision {
        val payload = DecideRequest(itemId, DeviceProfile.capabilities())
        val json = moshi.adapter(DecideRequest::class.java).toJson(payload)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .post(json.toRequestBody(jsonMedia))
            .build()
        return http.newCall(req).awaitResult { resp ->
            val body = resp.bodyOrThrow()
            moshi.adapter(PlaybackDecision::class.java).fromJson(body)
                ?: throw ApiException(resp.code, "Décision de lecture illisible")
        }
    }

    suspend fun getProgress(itemId: String): PlaybackProgress =
        withContext(Dispatchers.IO) {
            try { getJson<PlaybackProgress>("/api/playback/progress/$itemId") }
            catch (error: ApiException) {
                if (error.code == 404) PlaybackProgress() else throw error
            }
        }

    suspend fun setProgress(itemId: String, positionMs: Long, durationMs: Long, sequence: Long? = null) {
        withContext(Dispatchers.IO) {
            val ordered = sequence?.let { ",\"session_id\":\"$sessionId\",\"sequence\":$it" }.orEmpty()
            val json = """{"position_ms":$positionMs,"duration_ms":$durationMs$ordered}"""
            http.newCall(
                Request.Builder().url("$baseUrl/api/playback/progress/$itemId")
                    .post(json.toRequestBody(jsonMedia)).build()
            ).awaitResult { it.bodyOrThrow() }
        }
    }

    /** Configuration serveur complète (menu Réglages avancés). */
    suspend fun getSettings(): ServerConfig =
        withContext(Dispatchers.IO) { getJson("/api/settings") }

    /**
     * Applique un patch partiel à la config serveur et renvoie la config à jour.
     * `patchJson` ex. : {"transcoding":{"hdr_tone_mapping":"never"}}
     */
    suspend fun patchSettings(patchJson: String): ServerConfig =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/api/settings")
                .patch(patchJson.toRequestBody(jsonMedia))
                .build()
            http.newCall(req).awaitResult { resp ->
                val body = resp.bodyOrThrow()
                moshi.adapter(ServerConfig::class.java).fromJson(body)
                    ?: throw ApiException(resp.code, "Réglages illisibles")
            }
        }

    suspend fun stopHls(itemId: String) {
        withContext(Dispatchers.IO) {
            networkOrNull {
                http.newCall(
                    Request.Builder().url(abs("/stream/hls/$itemId")).delete().build()
                ).awaitResult { it.bodyOrThrow() }
            }
        }
    }

    companion object {
        private const val MAX_JSON_BYTES = 8 * 1024 * 1024

        fun create(sessionId: String = UUID.randomUUID().toString()): PluxyApi =
            PluxyApi(PluxyApplication.serverBaseUrl(PluxyApplication.instance), sessionId)
    }
}
