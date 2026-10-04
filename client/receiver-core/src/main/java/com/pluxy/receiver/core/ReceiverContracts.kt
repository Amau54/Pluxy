package com.pluxy.receiver.core

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal fun normalizeReceiverFormat(value: String): String = value.lowercase(Locale.ROOT).trim()
    .removePrefix("video/").removePrefix("audio/").let {
        when (it) {
            "avc", "x-h264" -> "h264"
            "h265", "x-h265" -> "hevc"
            "av01" -> "av1"
            "mpeg", "mpeg3", "x-mpeg-3" -> "mp3"
            "jpg" -> "jpeg"
            else -> it
        }
    }

enum class ReceiverProtocol { DLNA, GOOGLE_CAST }

data class ReceiverCapabilities(
    val containers: Set<String> = emptySet(),
    val videoCodecs: Set<String> = emptySet(),
    val audioCodecs: Set<String> = emptySet(),
    val imageMimeTypes: Set<String> = emptySet(),
    val maxVideoHeight: Int? = null,
    val supportsHdr: Boolean = false,
    val supportsSeek: Boolean = true,
    val source: String = "conservative",
    val rejectedContainers: Set<String> = emptySet(),
    val rejectedVideoCodecs: Set<String> = emptySet(),
    val rejectedAudioCodecs: Set<String> = emptySet(),
    val rejectedImageMimeTypes: Set<String> = emptySet(),
)

data class ReceiverDevice(
    val id: String,
    val name: String,
    val protocol: ReceiverProtocol,
    val description: String? = null,
    val capabilities: ReceiverCapabilities,
    /** Endpoints validés par le module de découverte, jamais fournis par l'utilisateur. */
    val attributes: Map<String, String> = emptyMap(),
)

data class RemoteMedia(
    val id: String,
    val title: String,
    val url: String,
    val mimeType: String,
    val kind: String,
    val container: String,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isHdr: Boolean = false,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
)

enum class PlaybackSuitability { DIRECT, ADAPTED, UNSUPPORTED }

data class ReceiverPlaybackPlan(
    val suitability: PlaybackSuitability,
    val summary: String,
    val reasons: List<String> = emptyList(),
)

enum class RemotePlaybackPhase { IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

enum class UnsupportedFormatComponent { CONTAINER, VIDEO, AUDIO, IMAGE }

data class RemotePlaybackStatus(
    val device: ReceiverDevice? = null,
    val media: RemoteMedia? = null,
    val phase: RemotePlaybackPhase = RemotePlaybackPhase.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val formatUnsupported: Boolean = false,
    val unsupportedComponent: UnsupportedFormatComponent? = null,
) {
    val active: Boolean
        get() = device != null && phase in setOf(
            RemotePlaybackPhase.CONNECTING,
            RemotePlaybackPhase.BUFFERING,
            RemotePlaybackPhase.PLAYING,
            RemotePlaybackPhase.PAUSED,
        )
}

interface ReceiverDiscovery : AutoCloseable {
    val devices: StateFlow<List<ReceiverDevice>>
    fun start()
    fun stop()
}

interface ReceiverController : AutoCloseable {
    val status: StateFlow<RemotePlaybackStatus>
    suspend fun load(media: RemoteMedia, playWhenReady: Boolean = true)
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    suspend fun stop()
}
