package com.pluxy.tv.player

/**
 * Courbe de scrub TV indépendante de la fréquence de répétition de la télécommande.
 * Un appui bref vaut toujours 5 secondes. Après le seuil de maintien, la distance
 * est intégrée par paliers continus : aucune répétition D-pad ne peut ajouter un saut
 * arbitraire ou rendre deux télécommandes radicalement différentes.
 */
object SeekAcceleration {
    const val TAP_MS = 5_000L
    const val HOLD_THRESHOLD_MS = 400L

    private data class Stage(val untilMs: Long, val rate: Long, val label: String)

    private val stages = listOf(
        Stage(800L, 10_000L, "×1"),
        Stage(1_600L, 20_000L, "×2"),
        Stage(3_200L, 40_000L, "×4"),
        Stage(5_200L, 80_000L, "×8"),
        Stage(8_000L, 160_000L, "×16"),
        Stage(12_000L, 320_000L, "×32"),
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
