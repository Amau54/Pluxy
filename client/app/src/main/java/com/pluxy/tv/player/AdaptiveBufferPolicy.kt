package com.pluxy.tv.player

/** Augmente la réserve après une coupure, sans dépasser la mémoire du lecteur. */
internal class AdaptiveBufferPolicy(
    private val buffer: PlaybackMemoryPolicy.RuntimeBuffer,
    private var sourceBitrateBps: Long,
) {
    private var lastRebufferAtMs: Long? = null
    private var inRebuffer = false
    var interruptions: Int = 0
        private set

    fun updateStreamBitrate(bitrateBps: Long) {
        if (bitrateBps > 0L) sourceBitrateBps = bitrateBps
    }

    fun observe(rebuffering: Boolean, rebufferAtMs: Long?) {
        if (rebuffering && (!inRebuffer ||
                (rebufferAtMs != null && rebufferAtMs != lastRebufferAtMs))) {
            interruptions = (interruptions + 1).coerceAtMost(4)
            lastRebufferAtMs = rebufferAtMs
        }
        inRebuffer = rebuffering
    }

    fun requiredPlaybackMs(rebuffering: Boolean, playbackSpeed: Float): Long {
        val speed = playbackSpeed.coerceAtLeast(0.1f)
        val desired = if (rebuffering) {
            val base = buffer.bufferForPlaybackAfterRebufferMs.coerceAtLeast(5_000)
            (base * Math.pow(1.5, interruptions.toDouble())).toLong().coerceAtMost(30_000L)
        } else buffer.bufferForPlaybackMs.toLong().coerceAtMost(1_500L)
        // Une marge pour les pics de débit et les pistes audio/sous-titres.
        val capacityMs = if (sourceBitrateBps > 0L) {
            (buffer.targetBufferBytes * 8.0 * 0.75 / sourceBitrateBps * 1000 / speed)
                .toLong().coerceAtLeast(250L)
        } else Long.MAX_VALUE
        return minOf(desired, capacityMs, (buffer.minBufferMs / speed).toLong())
    }
}
