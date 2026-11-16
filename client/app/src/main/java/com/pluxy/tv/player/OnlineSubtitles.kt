package com.pluxy.tv.player

import com.pluxy.tv.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** On-demand requests only; local media paths never leave the device. */
object OnlineSubtitles {
    data class Result(val title: String, val release: String, val language: String, val url: String)
    private const val LIMIT = 2 * 1024 * 1024
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun fetch(address: String): String {
        var url = address.toHttpUrl()
        repeat(4) {
            require(url.isHttps && url.host in setOf("api.thesubtitledb.org", "files.thesubtitledb.org"))
            val request = Request.Builder().url(url).header("User-Agent", "Pluxy/1.0").build()
            http.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    url = url.resolve(response.header("Location") ?: throw IOException("Redirection invalide"))
                        ?: throw IOException("Adresse invalide")
                } else {
                    if (!response.isSuccessful) throw IOException("Service de sous-titres indisponible (${response.code}). Réessayez plus tard.")
                    val body = response.body ?: throw IOException("Réponse vide")
                    if (body.contentLength() > LIMIT) throw IOException("Fichier trop volumineux")
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > LIMIT) throw IOException("Fichier trop volumineux")
                            output.write(buffer, 0, count)
                        }
                    }
                    return output.toString("UTF-8")
                }
            }
        }
        throw IOException("Trop de redirections")
    }

    suspend fun search(item: MediaItem, language: String, tmdbId: Int? = null): List<Result> = withContext(Dispatchers.IO) {
        val suffix = if (item.season != null && item.episode != null)
            "/season/${item.season}/episode/${item.episode}" else ""
        val path = if (tmdbId != null) "/v1/by-tmdb/${if (item.season != null) "tv" else "movie"}/$tmdbId" else "/v1/by-title"
        val url = "https://api.thesubtitledb.org$path$suffix".toHttpUrl().newBuilder()
            .addQueryParameter("q", item.seriesName ?: item.metadataTitle ?: item.title)
            .addQueryParameter("year", item.year?.toString())
            .addQueryParameter("lang", language).addQueryParameter("format", "srt")
            .addQueryParameter("client", "pluxy").build()
        val data = JSONObject(fetch(url.toString()))
        val title = data.optJSONObject("title")?.optString("name").orEmpty()
        if (title.isBlank()) return@withContext emptyList()
        val rows = data.optJSONObject("subtitles")?.optJSONArray("items") ?: return@withContext emptyList()
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            if (row.optString("format") != "srt" || row.optString("language") != language) null
            else Result(title, row.optString("release_name"), language, row.getString("download_url"))
        }
    }

    suspend fun download(result: Result, directory: File): File = withContext(Dispatchers.IO) {
        val text = fetch(result.url)
        if (!Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s+-->").containsMatchIn(text) ||
            text.trimStart().startsWith("<")) throw IOException("Le service n’a pas envoyé un sous-titre SRT valide")
        File.createTempFile("online-sub-", ".srt", directory).apply { writeText(text) }
    }

    // Remux seeks reset the stream clock. Keep downloaded captions on that clock.
    fun shifted(source: File, offsetMs: Long, target: File): File {
        if (offsetMs == 0L) return source
        val timing = Regex("(\\d{2,}):(\\d{2}):(\\d{2})[,.](\\d{3})")
        fun millis(match: MatchResult): Long {
            val (h, m, s, ms) = match.destructured
            return h.toLong()*3600000 + m.toLong()*60000 + s.toLong()*1000 + ms.toLong()
        }
        val blocks = source.readText().replace("\r\n", "\n").trim().split(Regex("\n\\s*\n"))
        val shifted = blocks.mapNotNull { block ->
            val lines = block.lines().toMutableList()
            val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) return@mapNotNull null
            val times = timing.findAll(lines[index]).toList()
            if (times.size != 2 || millis(times[1]) <= offsetMs) return@mapNotNull null
            lines[index] = lines[index].replace(timing) { match ->
                val time = (millis(match) - offsetMs).coerceAtLeast(0)
                String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", time/3600000, time/60000%60, time/1000%60, time%1000)
            }
            lines.joinToString("\n")
        }
        target.writeText(shifted.joinToString("\n\n") + "\n")
        return target
    }
}
