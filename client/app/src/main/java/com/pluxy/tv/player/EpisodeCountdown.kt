package com.pluxy.tv.player

/** Horloge monotone : un ralentissement de l'interface ne rallonge pas le décompte. */
internal fun episodeCountdownSeconds(deadlineMs: Long, nowMs: Long): Int =
    ((deadlineMs - nowMs).coerceAtLeast(0L).plus(999L) / 1_000L).toInt()
