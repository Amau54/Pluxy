package com.pluxy.tv.player

/** Detects stopped video output while the playback clock keeps advancing. */
internal class VideoRenderWatchPolicy {
    private var owner = ""
    private var lastFrames = -1
    private var lastDecoder = -1
    private var lastSampleMs = -1L
    private var lastPositionMs = -1L
    private var progressAtMs = -1L
    private var progressPositionMs = 0L
    private var recoveredAtMs = -1L
    private var recoveries = 0

    fun observe(mediaKey: String, nowMs: Long, positionMs: Long, frames: Int,
                decoder: Int, eligible: Boolean): Boolean {
        if (owner != mediaKey) {
            owner = mediaKey
            recoveredAtMs = -1L
            recoveries = 0
            clearObservation()
        }
        if (!eligible || frames <= 0 || nowMs < 0 || positionMs < 0) {
            clearObservation()
            return false
        }
        val interrupted = lastSampleMs < 0 || nowMs < lastSampleMs || nowMs - lastSampleMs > 3_000 ||
            positionMs < lastPositionMs || positionMs - lastPositionMs > 3_000
        if (interrupted || frames != lastFrames || decoder != lastDecoder) {
            progressAtMs = nowMs
            progressPositionMs = positionMs
        }
        lastFrames = frames
        lastDecoder = decoder
        lastSampleMs = nowMs
        lastPositionMs = positionMs
        if (nowMs - progressAtMs < 8_000 || positionMs - progressPositionMs < 4_000 || recoveries >= 2 ||
            recoveredAtMs >= 0 && nowMs - recoveredAtMs < 120_000) return false
        recoveredAtMs = nowMs
        recoveries++
        clearObservation()
        return true
    }

    fun clearObservation() {
        lastFrames = -1
        lastDecoder = -1
        lastSampleMs = -1L
        lastPositionMs = -1L
        progressAtMs = -1L
    }
}
