package com.pluxy.tv.player

/** Horloge monotone : un ralentissement de l'interface ne rallonge pas le décompte. */
internal fun episodeCountdownSeconds(deadlineMs: Long, nowMs: Long): Int =
    ((deadlineMs - nowMs).coerceAtLeast(0L).plus(999L) / 1_000L).toInt()

/** Position du média, indépendante des pauses et des attentes réseau. */
internal fun episodeEndRemainingMs(durationMs: Long, positionMs: Long): Long? =
    if (durationMs > 0 && positionMs >= 0) (durationMs - positionMs).takeIf { it in 0..5_000L } else null
