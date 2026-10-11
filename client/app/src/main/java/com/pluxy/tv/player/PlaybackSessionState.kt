package com.pluxy.tv.player

import com.pluxy.tv.api.MediaItem

/** User choices belong to a playback session, never to an ExoPlayer instance. */
data class PlaybackSessionState(
    val item: MediaItem,
    val serverUrl: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val subtitlesDisabled: Boolean = false,
    val audioChoice: PlaybackTrackChoice? = null,
    val subtitleChoice: PlaybackTrackChoice? = null,
    val aspectIndex: Int = 0,
    val forceDirect: Boolean = false,
    val compatibleAudio: Boolean = false,
    val audioFallbackLevel: Int = 0,
    val failedAudioCodec: String? = null,
    val strategyOverride: String? = null,
)

data class PlaybackTrackChoice(
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val channels: Int = -1,
    val trackId: String? = null,
) {
    /** Track groups/indices can change after a source or decoder rebuild. */
    fun matchScore(other: PlaybackTrackChoice): Int {
        var score = 0
        if (!language.isNullOrBlank() && language.equals(other.language, ignoreCase = true)) score += 16
        if (!trackId.isNullOrBlank() && trackId == other.trackId) score += 8
        if (!label.isNullOrBlank() && label == other.label) score += 4
        if (!mimeType.isNullOrBlank() && mimeType == other.mimeType) score += 2
        if (channels > 0 && channels == other.channels) score += 1
        return score
    }
}
