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
    @Json(name = "audio_codec") val audioCodec: String? = null,
    @Json(name = "audio_channels") val audioChannels: Int? = null,
    @Json(name = "is_hdr") val isHdr: Boolean = false,
    @Json(name = "group_count") val groupCount: Int = 1,
    @Json(name = "season_count") val seasonCount: Int = 0,
    val kind: String = "movie",                       // movie | series | photo | audio
    @Json(name = "added_at") val addedAt: Double = 0.0,
    @Json(name = "modified_at") val modifiedAt: Double = 0.0,
    @Json(name = "captured_at") val capturedAt: Double? = null,
    val genres: List<String> = emptyList(),
    @Json(name = "series_name") val seriesName: String? = null,
    @Json(name = "series_key") val seriesKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val artist: String? = null,
    val album: String? = null,
    @Json(name = "track_number") val trackNumber: Int? = null,
    @Json(name = "disc_number") val discNumber: Int? = null,
    val available: Boolean = true,
    val folder: String? = null,
    @Json(name = "folder_id") val folderId: String? = null,
    @Json(name = "relative_path") val relativePath: String? = null,
    @Json(name = "source_id") val sourceId: String? = null,
    @Json(name = "source_name") val sourceName: String? = null,
    @Json(name = "thumbnail_url") val thumbnailUrl: String? = null,
    @Json(name = "episode_still_url") val episodeStillUrl: String? = null,
    @Json(name = "external_subs") val externalSubs: List<String> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val year: Int? = null,
    @Json(name = "metadata_title") val metadataTitle: String? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "has_metadata") val hasMetadata: Boolean = false,
    val watched: Boolean = false,
    @Json(name = "in_progress") val inProgress: Boolean = false,
    val progress: Double = 0.0,                        // fraction 0..1 (reprise)
    @Json(name = "last_played_at") val lastPlayedAt: Double = 0.0,
)

/** Bounded page from a single catalogue revision. Legacy list remains supported. */
@JsonClass(generateAdapter = true)
data class LibraryPage(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val revision: String,
    @Json(name = "next_offset") val nextOffset: Int? = null,
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
    @Json(name = "server_id") val serverId: String? = null,
) {
    val baseUrl: String get() {
        val host = if (ip.contains(':') && !ip.startsWith('[')) "[$ip]" else ip
        return "http://$host:$port"
    }
}

/** Note d'un site (TMDB, IMDb, Rotten Tomatoes, Metacritic). */
@JsonClass(generateAdapter = true)
data class RatingItem(
    val source: String,
    val value: String,
)

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
    val ratings: List<RatingItem> = emptyList(),
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "backdrop_url") val backdropUrl: String? = null,
    @Json(name = "episode_still_url") val episodeStillUrl: String? = null,
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
    @Json(name = "supports_av1") val supportsAv1: Boolean = false,
    @Json(name = "supports_hdr10") val supportsHdr10: Boolean,
    @Json(name = "supports_hdr") val supportsHdr: Boolean,
    @Json(name = "supported_containers") val supportedContainers: List<String>,
    @Json(name = "supported_audio_codecs") val supportedAudioCodecs: List<String>,
    @Json(name = "supported_video_codecs") val supportedVideoCodecs: List<String> = emptyList(),
    @Json(name = "max_audio_channels") val maxAudioChannels: Int,
    @Json(name = "max_bitrate_mbps") val maxBitrateMbps: Int?,
    @Json(name = "max_video_height") val maxVideoHeight: Int,
    @Json(name = "screen_width") val screenWidth: Int,
    @Json(name = "screen_height") val screenHeight: Int,
    @Json(name = "profile_id") val profileId: String? = null,
    @Json(name = "profile_name") val profileName: String? = null,
    @Json(name = "profile_revision") val profileRevision: Int = 0,
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
    val playable: Boolean = true,
    val severity: String = "success",
    val summary: String = "",
    @Json(name = "video_action") val videoAction: String = "copy",
    @Json(name = "audio_action") val audioAction: String = "copy",
    @Json(name = "target_audio_codec") val targetAudioCodec: String? = null,
    @Json(name = "target_audio_channels") val targetAudioChannels: Int? = null,
    @Json(name = "profile_id") val profileId: String? = null,
    @Json(name = "profile_name") val profileName: String? = null,
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
    @Json(name = "server_kind") val serverKind: String = "pc",
    @Json(name = "transcoding_available") val transcodingAvailable: Boolean = true,
    @Json(name = "direct_stream_available") val directStreamAvailable: Boolean = true,
    @Json(name = "direct_stream_seek_available") val directStreamSeekAvailable: Boolean = false,
)

// --------------------------------------------------------------------------- //
//  Configuration serveur (menu Réglages avancés) — GET/PATCH /api/settings      //
//  Seuls les champs pilotables depuis la TV sont modélisés ; Moshi ignore le    //
//  reste de la config renvoyée par le serveur.                                  //
// --------------------------------------------------------------------------- //
@JsonClass(generateAdapter = true)
data class TranscodingConfig(
    @Json(name = "hardware_acceleration") val hardwareAcceleration: Boolean = true,
    val preset: String = "p5",
    @Json(name = "force_transcode") val forceTranscode: Boolean = false,
    @Json(name = "prefer_direct_play") val preferDirectPlay: Boolean = true,
    @Json(name = "max_bitrate_mbps") val maxBitrateMbps: Int = 50,
    @Json(name = "hdr_tone_mapping") val hdrToneMapping: String = "never",
)

@JsonClass(generateAdapter = true)
data class AudioConfig(
    /** Copie le flux audio d'origine sans le réencoder (DTS-HD, TrueHD, Atmos…).
     *  L'ampli HDMI/ARC/eARC le décode directement. Actif par défaut. */
    @Json(name = "force_audio_passthrough") val forceAudioPassthrough: Boolean = true,
    @Json(name = "target_codec") val targetCodec: String = "eac3",
    @Json(name = "target_channels") val targetChannels: Int = 6,
    @Json(name = "bitrate_kbps") val bitrateKbps: Int = 640,
    @Json(name = "downmix_lossless") val downmixLossless: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class NetworkConfig(
    @Json(name = "direct_play_enabled") val directPlayEnabled: Boolean = true,
    @Json(name = "direct_stream_enabled") val directStreamEnabled: Boolean = true,
    @Json(name = "hls_segment_duration") val hlsSegmentDuration: Int = 3,
)

@JsonClass(generateAdapter = true)
data class ClientBufferConfig(
    @Json(name = "min_buffer_ms") val minBufferMs: Int = 50_000,
    @Json(name = "max_buffer_ms") val maxBufferMs: Int = 120_000,
)

@JsonClass(generateAdapter = true)
data class ServerSection(
    val name: String = "Pluxy",
    val autostart: Boolean = false,
    val platform: String = "pc",
    @Json(name = "transcoding_available") val transcodingAvailable: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class ServerConfig(
    val transcoding: TranscodingConfig = TranscodingConfig(),
    val audio: AudioConfig = AudioConfig(),
    val network: NetworkConfig = NetworkConfig(),
    @Json(name = "client_buffer") val clientBuffer: ClientBufferConfig = ClientBufferConfig(),
    val server: ServerSection = ServerSection(),
)

/** Position de reprise (watch-state) renvoyée/poussée par /api/playback/progress */
@JsonClass(generateAdapter = true)
data class PlaybackProgress(
    @Json(name = "position_ms") val positionMs: Long = 0,
    @Json(name = "duration_ms") val durationMs: Long = 0,
    val watched: Boolean = false,
)
