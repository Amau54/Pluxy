package com.pluxy.server.library

import java.text.Normalizer
import java.util.Locale

internal data class CreditText(val roles: Set<String> = emptySet(), val nameCard: Boolean = false,
    val dense: Boolean = false, val signature: String = "", val blank: Boolean = false, val roster: Boolean = false)

internal object CreditTextPolicy {
    private val roles = mapOf(
        "production" to "produced by|produit par|producer|producers|producteur|producteurs|production",
        "direction" to "directed by|realise par|un film de|director|directeur|realisation|realisateur",
        "writing" to "written by|ecrit par|screenplay|scenario|scenariste",
        "music" to "music|musique|composer|compositeur",
        "editing" to "edited by|editor|editing|montage|monteur",
        "casting" to "cast|casting|distribution|starring",
        "camera" to "photography|camera|cinematography|cinematographer|operateur",
        "design" to "costume|costumes|designer|decors",
        "effects" to "visual effects|special effects|vfx|effets",
        "sound" to "sound|audio|mixing|mixage",
        "crew" to "assistant|assistants|coordinator|supervisor|department|technician|technicians|artist|artists|animation"
    ).mapValues { Regex("\\b(${it.value})\\b") }

    fun summarize(lines: List<String>, blank: Boolean = false): CreditText {
        val text = lines.joinToString(" ")
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
        val matched = roles.filterValues { it.containsMatchIn(normalized) }.keys
        val letters = text.filter { it.isLetter() }
        val words = Regex("[a-zA-ZÀ-ÿ]+").findAll(text).count()
        val namedLine = lines.filter { Regex("[a-zA-ZÀ-ÿ]+").findAll(it).count() >= 2 }.map { line -> line.filter { it.isLetter() } }
            .any { it.length >= 8 && it.count { c -> c.isUpperCase() }.toDouble() / it.length >= .85 }
        val name = lines.size in 1..8 && words in 2..40 && letters.length >= 8 && (letters.count { it.isUpperCase() }.toDouble() / letters.length >= .65 || namedLine)
        val roster = lines.size >= 8 && words >= 16 && letters.length >= 30 && letters.count { it.isUpperCase() }.toDouble() / letters.length >= .65 &&
            lines.count { Regex("[.!?,:;]").containsMatchIn(it) } <= lines.size * .2
        return CreditText(matched, name, matched.size >= 2 && lines.size >= 4, normalized, blank, roster)
    }

    fun opening(samples: List<Pair<Double, CreditText>>): Pair<Double, Double>? {
        val group = ArrayList<Pair<Double, CreditText>>()
        var gaps = 0
        for ((seconds, text) in samples + listOf(Double.POSITIVE_INFINITY to CreditText(), Double.POSITIVE_INFINITY to CreditText())) {
            if (text.nameCard || text.dense) { group += seconds to text; gaps = 0 }
            else {
                gaps++
                if (gaps < 2) continue
                val matched = group.flatMap { it.second.roles }.toSet()
                val distinct = group.filter { it.second.roles.isNotEmpty() }.map { it.second.signature }.toSet()
                if (group.isNotEmpty() && matched.size >= 3 && distinct.size >= 3) {
                    val start = group.first().first + 1
                    val end = group.filter { it.second.roles.isNotEmpty() }.maxOf { it.first }
                    if (end - start in 20.0..240.0) return start to end
                }
                group.clear()
            }
        }
        return null
    }
}
