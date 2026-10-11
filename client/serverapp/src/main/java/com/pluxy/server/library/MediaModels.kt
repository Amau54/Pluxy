package com.pluxy.server.library

import com.pluxy.server.WatchState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SubtitleEntry(
    val uri: String,
    val name: String,
    val format: String,
    val language: String?,
)

data class MediaEntry(
    val id: String,
    val uri: String,
    val fileName: String,
    val title: String,
    val parsedYear: Int?,
    val container: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAtMs: Long,
    val durationSeconds: Double,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioChannels: Int?,
    val isHdr: Boolean,
    val kind: String,
    val seriesName: String?,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    val artist: String?,
    val album: String?,
    val folder: String?,
    val subtitles: List<SubtitleEntry>,
    val probeRevision: Int = 4,
    val sourceKey: String? = null,
    val available: Boolean = true,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val addedAtMs: Long = modifiedAtMs,
    val capturedAtMs: Long? = null,
    val folderId: String? = null,
    val relativePath: String? = null,
    val sourceName: String? = null,
    val sourceId: String? = null,
    val embeddedTitle: String? = null,
    val fingerprint: String = "",
) {
    fun publicJson(metadata: JSONObject?, watch: WatchState): JSONObject {
        val videoMetadata = metadata.takeIf { kind == "movie" || kind == "series" }
        val durationMs = watch.durationMs.takeIf { it > 0 }
            ?: (durationSeconds * 1000.0).toLong().coerceAtLeast(0)
        val progress = if (!watch.watched && watch.positionMs > 0 && durationMs > 0) {
            (watch.positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        val metadataMatched = videoMetadata?.optBoolean("matched", false) == true
        val poster = videoMetadata?.optString("poster_url", "")?.takeIf { it.isNotBlank() }
        val year = videoMetadata?.optInt("year", 0)?.takeIf { it > 0 } ?: parsedYear

        return JSONObject().apply {
            put("id", id)
            put("title", title)
            putNullable("metadata_title", videoMetadata?.takeIf { metadataMatched }?.optStringOrNull("title"))
            put("path", "")
            put("container", container)
            put("size", size)
            put("duration", durationSeconds)
            putNullable("width", width)
            putNullable("height", height)
            putNullable("video_codec", videoCodec)
            putNullable("audio_codec", audioCodec)
            putNullable("audio_channels", audioChannels)
            put("is_hdr", isHdr)
            put("kind", kind)
            put("available", available)
            putNullable("disc_number", discNumber)
            putNullable("track_number", trackNumber)
            put("added_at", addedAtMs / 1000.0)
            put("modified_at", modifiedAtMs / 1000.0)
            putNullable("captured_at", capturedAtMs?.let { it / 1000.0 })
            put("genres", JSONArray(metadataGenres(videoMetadata)))
            putNullable("series_name", seriesName)
            putNullable("series_key", seriesKey)
            putNullable("season", season)
            putNullable("episode", episode)
            putNullable("artist", artist)
            putNullable("album", album)
            putNullable("folder", folder)
            putNullable("folder_id", folderId ?: folderIdentity(sourceKey ?: "", folder.orEmpty()))
            putNullable("relative_path", relativePath)
            putNullable("source_id", sourceId ?: sourceKey?.let { folderIdentity(it, "") })
            putNullable("source_name", sourceName)
            putNullable("thumbnail_url", localThumbnailUrl(kind, id))
            put("external_subs", JSONArray().apply {
                subtitles.forEach { put("") }
            })
            put("subtitles", JSONArray().apply {
                subtitles.forEachIndexed { index, subtitle ->
                    put(JSONObject().apply {
                        put("index", index)
                        putNullable("lang", subtitle.language)
                        put("format", subtitle.format)
                        put("label", buildString {
                            append(subtitle.language?.uppercase() ?: "Sous-titres")
                            append(" (").append(subtitle.format).append(")")
                        })
                    })
                }
            })
            putNullable("year", year)
            putNullable("poster_url", poster)
            putNullable("episode_still_url", videoMetadata?.takeIf { kind == "series" }?.optStringOrNull("episode_still_url"))
            put("has_metadata", metadataMatched)
            put("watched", watch.watched)
            put("in_progress", !watch.watched && watch.positionMs > 0)
            put("progress", progress)
            put("last_played_at", watch.lastPlayedAtMs / 1000.0)
        }
    }

    fun persistedJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("file_name", fileName)
        put("title", title)
        putNullable("parsed_year", parsedYear)
        put("container", container)
        putNullable("mime_type", mimeType)
        put("size", size)
        put("modified_at_ms", modifiedAtMs)
        put("fingerprint", fingerprint)
        put("added_at_ms", addedAtMs)
        putNullable("captured_at_ms", capturedAtMs)
        put("duration_seconds", durationSeconds)
        putNullable("width", width)
        putNullable("height", height)
        putNullable("video_codec", videoCodec)
        putNullable("audio_codec", audioCodec)
        putNullable("audio_channels", audioChannels)
        put("is_hdr", isHdr)
        put("kind", kind)
        putNullable("series_name", seriesName)
        putNullable("series_key", seriesKey)
        putNullable("season", season)
        putNullable("episode", episode)
        putNullable("artist", artist)
        putNullable("album", album)
        putNullable("folder", folder)
        putNullable("folder_id", folderId)
        putNullable("relative_path", relativePath)
        putNullable("source_name", sourceName)
        putNullable("source_id", sourceId)
        putNullable("embedded_title", embeddedTitle)
        put("subtitles", JSONArray().apply {
            subtitles.forEach { sub ->
                put(JSONObject().apply {
                    put("uri", sub.uri)
                    put("name", sub.name)
                    put("format", sub.format)
                    putNullable("language", sub.language)
                })
            }
        })
        put("probe_revision", probeRevision)
        putNullable("source_key", sourceKey)
        put("available", available)
        putNullable("disc_number", discNumber)
        putNullable("track_number", trackNumber)
    }

    companion object {
        fun fromPersistedJson(json: JSONObject): MediaEntry = MediaEntry(
            id = json.getString("id"),
            uri = json.getString("uri"),
            fileName = json.getString("file_name"),
            title = json.getString("title"),
            parsedYear = json.optIntOrNull("parsed_year"),
            container = json.getString("container"),
            mimeType = json.optStringOrNull("mime_type"),
            size = json.optLong("size", 0),
            modifiedAtMs = json.optLong("modified_at_ms", 0),
            fingerprint = json.optString("fingerprint", ""),
            durationSeconds = json.optDouble("duration_seconds", 0.0),
            width = json.optIntOrNull("width"),
            height = json.optIntOrNull("height"),
            videoCodec = json.optStringOrNull("video_codec"),
            audioCodec = json.optStringOrNull("audio_codec"),
            audioChannels = json.optIntOrNull("audio_channels"),
            isHdr = json.optBoolean("is_hdr", false),
            kind = json.optString("kind", "movie"),
            seriesName = json.optStringOrNull("series_name"),
            seriesKey = json.optStringOrNull("series_key"),
            season = json.optIntOrNull("season"),
            episode = json.optIntOrNull("episode"),
            artist = json.optStringOrNull("artist"),
            album = json.optStringOrNull("album"),
            folder = json.optStringOrNull("folder"),
            subtitles = json.optJSONArray("subtitles")?.let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        val sub = array.optJSONObject(i) ?: continue
                        add(SubtitleEntry(
                            uri = sub.optString("uri"),
                            name = sub.optString("name"),
                            format = sub.optString("format", "srt"),
                            language = sub.optStringOrNull("language"),
                        ))
                    }
                }
            }.orEmpty(),
            probeRevision = json.optInt("probe_revision", 0).coerceAtLeast(0),
            sourceKey = json.optStringOrNull("source_key"),
            available = json.optBoolean("available", true),
            discNumber = json.optIntOrNull("disc_number"),
            trackNumber = json.optIntOrNull("track_number"),
            addedAtMs = migratedAddedAt(if (json.has("added_at_ms") && !json.isNull("added_at_ms")) json.optLong("added_at_ms", 0) else null,
                json.optLong("modified_at_ms", 0)),
            capturedAtMs = json.optLong("captured_at_ms", 0).takeIf { it > 0 },
            folderId = json.optStringOrNull("folder_id"),
            relativePath = json.optStringOrNull("relative_path"),
            sourceName = json.optStringOrNull("source_name"),
            sourceId = json.optStringOrNull("source_id"),
            embeddedTitle = json.optStringOrNull("embedded_title"),
        )
    }
}

/** Old indexes only recorded modification: retain their ordering without inventing a date. */
internal fun migratedAddedAt(addedAtMs: Long?, modifiedAtMs: Long): Long =
    addedAtMs?.coerceAtLeast(0) ?: modifiedAtMs.coerceAtLeast(0)

internal fun metadataGenres(metadata: JSONObject?): List<String> = buildList {
    val values = metadata?.optJSONArray("genres") ?: return@buildList
    for (index in 0 until values.length()) {
        val name = values.opt(index) as? String ?: continue
        name.trim().takeIf { it.isNotEmpty() && it !in this }?.let(::add)
    }
}

/** Distinguish identically named folders without sending content URIs to a client. */
internal fun folderIdentity(source: String, folder: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$source\u0000$folder".toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun localThumbnailUrl(kind: String, id: String): String? =
    if (kind in setOf("photo", "audio", "movie", "series", "clip")) {
        "/image/thumbnail/$id?v=2"
    } else {
        null
    }

internal fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}

internal fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key).takeIf { it != 0 }
}
