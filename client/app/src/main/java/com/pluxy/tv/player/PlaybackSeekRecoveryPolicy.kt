package com.pluxy.tv.player

/** Reprise d'un seek sans confondre un rendu audio bloqué et un réseau lent. */
internal object PlaybackSeekRecoveryPolicy {
    enum class Action { REBUILD, TEMPORAL_COPY, NORMAL_FALLBACK }

    fun action(direct: Boolean, recentSeek: Boolean, remux: Boolean,
               temporalAvailable: Boolean, recoveries: Int): Action = when {
        !direct || !recentSeek -> Action.NORMAL_FALLBACK
        recoveries == 0 -> Action.REBUILD
        recoveries == 1 && !remux && temporalAvailable -> Action.TEMPORAL_COPY
        else -> Action.NORMAL_FALLBACK
    }

    fun needsAudioReset(direct: Boolean, mime: String?, forcedPassthrough: Boolean): Boolean =
        direct && (forcedPassthrough || mime?.lowercase() in setOf(
            "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd",
            "audio/vnd.dts", "audio/vnd.dts.hd", "audio/ac4",
        ))

    fun renderedBufferStuck(elapsedMs: Long, bufferedMs: Long, direct: Boolean,
                            recentSeek: Boolean): Boolean =
        direct && recentSeek && elapsedMs >= 6_000L && bufferedMs >= 5_000L
}
