package com.pluxy.tv.api

import com.pluxy.tv.PluxyApplication
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Erreur réseau lisible (statut HTTP + détail) — évite les messages opaques. */
class ApiException(val code: Int, message: String) : IOException(message)

/** Client réseau Pluxy (OkHttp + Moshi). Toutes les méthodes sont suspend. */
class PluxyApi(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonMedia = "application/json".toMediaType()

    fun abs(relative: String): String =
        if (relative.startsWith("http")) relative else baseUrl + relative

    // --- Helpers --------------------------------------------------------- //
    private fun Response.bodyOrThrow(): String {
        val txt = body?.string().orEmpty()
        if (!isSuccessful) {
            throw ApiException(code, "Serveur: HTTP $code ${message}".trim())
        }
        return txt
    }

    private inline fun <reified T> getJson(path: String): T = http
        .newCall(Request.Builder().url("$baseUrl$path").build())
        .execute().use { resp ->
            val body = resp.bodyOrThrow()
            moshi.adapter(T::class.java).fromJson(body)
                ?: throw ApiException(resp.code, "Réponse vide pour $path")
        }

    // --- Endpoints ------------------------------------------------------- //
    suspend fun listItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$baseUrl/api/library/items").build())
            .execute().use { resp ->
                val body = resp.bodyOrThrow()
                val type = Types.newParameterizedType(List::class.java, MediaItem::class.java)
                moshi.adapter<List<MediaItem>>(type).fromJson(body) ?: emptyList()
            }
    }

    suspend fun rescan(): Unit = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url("$baseUrl/api/library/scan")
                .post("".toRequestBody(jsonMedia)).build()
        ).execute().use { it.bodyOrThrow() }
    }

    suspend fun scanStatus(): ScanStatus =
        withContext(Dispatchers.IO) {
            runCatching { getJson<ScanStatus>("/api/library/scan-status") }
                .getOrDefault(ScanStatus())
        }

    suspend fun clientRuntime(): ClientRuntime =
        withContext(Dispatchers.IO) { getJson("/api/settings/client") }

    suspend fun metadata(itemId: String, refresh: Boolean = false): MovieMetadata? =
        withContext(Dispatchers.IO) {
            runCatching {
                getJson<MovieMetadata>(
                    "/api/library/items/$itemId/metadata" + if (refresh) "?refresh=true" else ""
                )
            }.getOrNull()
        }

    suspend fun serverInfo(): ServerInfo? =
        withContext(Dispatchers.IO) { runCatching { getJson<ServerInfo>("/api/server/info") }.getOrNull() }

    @OptIn(UnstableApi::class)
    suspend fun decide(itemId: String): PlaybackDecision = withContext(Dispatchers.IO) {
        val payload = DecideRequest(itemId, DeviceProfile.capabilities())
        val json = moshi.adapter(DecideRequest::class.java).toJson(payload)
        val req = Request.Builder()
            .url("$baseUrl/api/playback/decide")
            .post(json.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.bodyOrThrow()
            moshi.adapter(PlaybackDecision::class.java).fromJson(body)
                ?: throw ApiException(resp.code, "Décision de lecture illisible")
        }
    }

    suspend fun getProgress(itemId: String): PlaybackProgress =
        withContext(Dispatchers.IO) {
            runCatching { getJson<PlaybackProgress>("/api/playback/progress/$itemId") }
                .getOrDefault(PlaybackProgress())
        }

    suspend fun setProgress(itemId: String, positionMs: Long, durationMs: Long) {
        withContext(Dispatchers.IO) {
            val json = """{"position_ms":$positionMs,"duration_ms":$durationMs}"""
            runCatching {
                http.newCall(
                    Request.Builder().url("$baseUrl/api/playback/progress/$itemId")
                        .post(json.toRequestBody(jsonMedia)).build()
                ).execute().close()
            }
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
            http.newCall(req).execute().use { resp ->
                val body = resp.bodyOrThrow()
                moshi.adapter(ServerConfig::class.java).fromJson(body)
                    ?: throw ApiException(resp.code, "Réglages illisibles")
            }
        }

    suspend fun stopHls(itemId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    Request.Builder().url("$baseUrl/stream/hls/$itemId").delete().build()
                ).execute().close()
            }
        }
    }

    companion object {
        fun create(): PluxyApi =
            PluxyApi(PluxyApplication.serverBaseUrl(PluxyApplication.instance))
    }
}
