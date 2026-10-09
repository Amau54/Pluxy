package com.pluxy.tv.player

/**
 * Courbe de scrub TV indépendante de la fréquence de répétition de la télécommande.
 * Un appui bref vaut toujours 10 secondes. Après le seuil de maintien, la distance
 * est intégrée par paliers continus : aucune répétition D-pad ne peut ajouter un saut
 * arbitraire ou rendre deux télécommandes radicalement différentes.
 */
object SeekAcceleration {
    const val TAP_MS = 10_000L
    const val HOLD_THRESHOLD_MS = 400L

    private data class Stage(val untilMs: Long, val rate: Long, val label: String)

    private val stages = listOf(
        Stage(1_000L, 10_000L, "×1"),
        Stage(2_000L, 20_000L, "×2"),
        Stage(4_000L, 40_000L, "×4"),
        Stage(6_500L, 80_000L, "×8"),
        Stage(10_000L, 160_000L, "×16"),
        Stage(15_000L, 320_000L, "×32"),
        Stage(Long.MAX_VALUE, 640_000L, "×64"),
    )

    fun distanceMs(elapsedMs: Long): Long {
        var remaining = (elapsedMs.coerceAtLeast(0L) - HOLD_THRESHOLD_MS).coerceAtLeast(0L)
        var cursor = HOLD_THRESHOLD_MS
        var distance = TAP_MS
        for (stage in stages) {
            if (remaining <= 0L) break
            val span = (stage.untilMs - cursor).coerceAtLeast(0L)
            val used = minOf(remaining, span)
            distance += used * stage.rate / 1_000L
            remaining -= used
            cursor = stage.untilMs
        }
        return distance
    }

    fun speedLabel(elapsedMs: Long): String {
        if (elapsedMs < HOLD_THRESHOLD_MS) return "Précis"
        return stages.first { elapsedMs < it.untilMs }.label
    }
}
