package com.pluxy.tv.player

/** A Matroska index can download before ExoPlayer reports buffered media time. */
internal object PlaybackBufferWatchPolicy {
    const val BYTE_PROGRESS_THRESHOLD = 64 * 1024L

    fun isStuck(elapsedMs: Long, idleMs: Long, isHls: Boolean): Boolean =
        idleMs >= (if (isHls) 45_000L else 20_000L) ||
            elapsedMs >= (if (isHls) 180_000L else 90_000L)
}
