package com.pluxy.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/** La limite d'octets reste celle de Media3 ; seule la réserve de reprise évolue. */
@OptIn(UnstableApi::class)
internal class AdaptiveLoadControl(
    private val delegate: LoadControl,
    private val buffer: PlaybackMemoryPolicy.RuntimeBuffer,
    sourceBitrateBps: Long,
) : LoadControl {
    private val policy = AdaptiveBufferPolicy(buffer, sourceBitrateBps)

    // Les méthodes Java par défaut de LoadControl ne sont pas transférées par
    // la délégation Kotlin : elles doivent appeler explicitement le contrôleur.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline,
                                         mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
                                  trackSelections: Array<out ExoTrackSelection?>) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
        val formats = trackSelections.mapNotNull { it?.selectedFormat }
        // Une version adaptée peut avoir un débit très différent du fichier source.
        // Ne pas remplacer l'estimation vidéo par le seul débit de la piste audio.
        if (formats.any { it.sampleMimeType?.startsWith("video/") == true && it.bitrate > 0 }) {
            policy.updateStreamBitrate(formats.sumOf { it.bitrate.coerceAtLeast(0).toLong() })
        }
    }

    private fun observe(parameters: LoadControl.Parameters) {
        val previous = policy.interruptions
        policy.observe(parameters.rebuffering,
            parameters.lastRebufferRealtimeMs.takeIf { it != C.TIME_UNSET })
        if (previous != policy.interruptions) {
            com.pluxy.tv.util.Logger.log("buffer", "coupure=${policy.interruptions} " +
                "réserve=${policy.requiredPlaybackMs(true, parameters.playbackSpeed)}ms " +
                "budget=${buffer.targetBufferBytes / 1024 / 1024}MiB")
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        observe(parameters)
        return delegate.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        observe(parameters)
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) return delegate.shouldStartPlayback(parameters)
        val bufferedMs = parameters.bufferedDurationUs / 1000 / parameters.playbackSpeed.coerceAtLeast(0.1f)
        if (bufferedMs >= policy.requiredPlaybackMs(parameters.rebuffering, parameters.playbackSpeed)) return true
        // Un pic de débit peut remplir le budget avant la durée visée. Attendre
        // davantage serait impossible : Media3 ne charge plus une fois ce budget plein.
        return parameters.bufferedDurationUs > 0L &&
            delegate.getAllocator(parameters.playerId).totalBytesAllocated >= buffer.targetBufferBytes &&
            delegate.shouldStartPlayback(parameters)
    }
}
