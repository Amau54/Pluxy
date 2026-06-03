package com.pluxy.tv.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Élément de bibliothèque renvoyé par /api/library/items */
@JsonClass(generateAdapter = true)
data class MediaItem(
    val id: String,
    val title: String,
    val path: String,
    val container: String,
    val size: Long,
    val duration: Double,
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "video_codec") val videoCodec: String? = null,
    @Json(name = "is_hdr") val isHdr: Boolean = false,
    @Json(name = "external_subs") val externalSubs: List<String> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val year: Int? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "has_metadata") val hasMetadata: Boolean = false,
)

/** Statut du scan de bibliothèque (/api/library/scan-status). */
@JsonClass(generateAdapter = true)
data class ScanStatus(
    val scanning: Boolean = false,
    val count: Int = 0,
)

/** Sous-titre externe décrit par le serveur (langue + format). */
@JsonClass(generateAdapter = true)
data class SubtitleTrack(
    val index: Int,
    val lang: String? = null,
    val format: String = "srt",
    val label: String = "",
)

/** Serveur découvert sur le LAN (broadcast UDP ou /api/server/info). */
@JsonClass(generateAdapter = true)
data class ServerInfo(
    val app: String = "pluxy",
    val name: String = "Pluxy",
    val ip: String,
    val port: Int = 8420,
    val version: String? = null,
) {
    val baseUrl: String get() = "http://$ip:$port"
}

/** Membre du casting. */
@JsonClass(generateAdapter = true)
data class CastMember(
    val name: String,
    val character: String? = null,
    @Json(name = "profile_url") val profileUrl: String? = null,
)

/** Métadonnées enrichies (TMDB) renvoyées par /api/library/items/{id}/metadata */
@JsonClass(generateAdapter = true)
data class MovieMetadata(
    @Json(name = "tmdb_id") val tmdbId: Int? = null,
    val title: String,
    @Json(name = "original_title") val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val rating: Double? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "backdrop_url") val backdropUrl: String? = null,
    val cast: List<CastMember> = emptyList(),
    val director: String? = null,
    @Json(name = "trailer_youtube_key") val trailerYoutubeKey: String? = null,
    @Json(name = "trailer_url") val trailerUrl: String? = null,
    val matched: Boolean = false,
)

/** Capacités envoyées au serveur pour le moteur de décision. */
@JsonClass(generateAdapter = true)
data class ClientCapabilities(
    @Json(name = "supports_hevc") val supportsHevc: Boolean,
    @Json(name = "supports_hevc_10bit") val supportsHevc10bit: Boolean,
    @Json(name = "supports_h264") val supportsH264: Boolean,
    @Json(name = "supports_hdr10") val supportsHdr10: Boolean,
    @Json(name = "supports_hdr") val supportsHdr: Boolean,
    @Json(name = "supported_containers") val supportedContainers: List<String>,
    @Json(name = "supported_audio_codecs") val supportedAudioCodecs: List<String>,
    @Json(name = "max_audio_channels") val maxAudioChannels: Int,
    @Json(name = "max_bitrate_mbps") val maxBitrateMbps: Int?,
    @Json(name = "max_video_height") val maxVideoHeight: Int,
    @Json(name = "screen_width") val screenWidth: Int,
    @Json(name = "screen_height") val screenHeight: Int,
)

@JsonClass(generateAdapter = true)
data class DecideRequest(
    @Json(name = "item_id") val itemId: String,
    val capabilities: ClientCapabilities,
)

/** Décision de lecture renvoyée par /api/playback/decide */
@JsonClass(generateAdapter = true)
data class PlaybackDecision(
    val mode: String,                                   // direct_play | direct_stream | transcode
    val reasons: List<String> = emptyList(),
    val compat: Boolean = false,
    @Json(name = "tone_map") val toneMap: Boolean = false,
    @Json(name = "target_bitrate_mbps") val targetBitrateMbps: Int? = null,
    val delivery: String = "direct",                    // direct | hls
    @Json(name = "stream_url") val streamUrl: String,
)

/** Réglages runtime (buffer ExoPlayer) renvoyés par /api/settings/client */
@JsonClass(generateAdapter = true)
data class BufferConfig(
    @Json(name = "min_buffer_ms") val minBufferMs: Int,
    @Json(name = "max_buffer_ms") val maxBufferMs: Int,
    @Json(name = "buffer_for_playback_ms") val bufferForPlaybackMs: Int,
    @Json(name = "buffer_for_playback_after_rebuffer_ms") val bufferForPlaybackAfterRebufferMs: Int,
    @Json(name = "target_buffer_bytes_mb") val targetBufferBytesMb: Int,
    @Json(name = "back_buffer_ms") val backBufferMs: Int,
)

@JsonClass(generateAdapter = true)
data class SubtitleConfig(
    @Json(name = "external_extensions") val externalExtensions: List<String> = emptyList(),
    @Json(name = "prefer_external") val preferExternal: Boolean = true,
    @Json(name = "burn_in") val burnIn: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ClientRuntime(
    val buffer: BufferConfig,
    val subtitles: SubtitleConfig,
    @Json(name = "server_name") val serverName: String,
)

/** Position de reprise (watch-state) renvoyée/poussée par /api/playback/progress */
@JsonClass(generateAdapter = true)
data class PlaybackProgress(
    @Json(name = "position_ms") val positionMs: Long = 0,
    @Json(name = "duration_ms") val durationMs: Long = 0,
    val watched: Boolean = false,
)
