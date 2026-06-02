package com.pluxy.tv.api

import com.pluxy.tv.PluxyApplication
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Client réseau Pluxy (OkHttp + Moshi). Toutes les méthodes sont suspend. */
class PluxyApi(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonMedia = "application/json".toMediaType()

    fun abs(relative: String): String =
        if (relative.startsWith("http")) relative else baseUrl + relative

    suspend fun listItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/library/items").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val type = Types.newParameterizedType(List::class.java, MediaItem::class.java)
            moshi.adapter<List<MediaItem>>(type).fromJson(body) ?: emptyList()
        }
    }

    suspend fun clientRuntime(): ClientRuntime = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/settings/client").build()
        http.newCall(req).execute().use { resp ->
            moshi.adapter(ClientRuntime::class.java).fromJson(resp.body!!.string())!!
        }
    }

    suspend fun decide(itemId: String): PlaybackDecision = withContext(Dispatchers.IO) {
        val payload = DecideRequest(itemId, DeviceProfile.capabilities())
        val json = moshi.adapter(DecideRequest::class.java).toJson(payload)
        val req = Request.Builder()
            .url("$baseUrl/api/playback/decide")
            .post(json.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            moshi.adapter(PlaybackDecision::class.java).fromJson(resp.body!!.string())!!
        }
    }

    companion object {
        fun create(): PluxyApi =
            PluxyApi(PluxyApplication.serverBaseUrl(PluxyApplication.instance))
    }
}
