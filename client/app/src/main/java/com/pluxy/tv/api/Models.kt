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
)

/** Capacités envoyées au serveur pour le moteur de décision. */
@JsonClass(generateAdapter = true)
data class ClientCapabilities(
    @Json(name = "supports_hevc") val supportsHevc: Boolean,
    @Json(name = "supports_h264") val supportsH264: Boolean,
    @Json(name = "supports_hdr10") val supportsHdr10: Boolean,
    @Json(name = "supports_hdr") val supportsHdr: Boolean,
    @Json(name = "supported_containers") val supportedContainers: List<String>,
    @Json(name = "supported_audio_codecs") val supportedAudioCodecs: List<String>,
    @Json(name = "max_audio_channels") val maxAudioChannels: Int,
    @Json(name = "max_bitrate_mbps") val maxBitrateMbps: Int?,
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
