package com.pluxy.server.metadata

import java.text.Normalizer
import java.util.Locale

/** Compare les titres avant d'attribuer une affiche, accents et ponctuation compris. */
internal object TitleMatch {
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    fun matches(query: String, vararg titles: String): Boolean {
        val a = key(query)
        if (a.isEmpty()) return false
        return titles.any { title ->
            val b = key(title)
            if (a == b) true else if (minOf(a.length, b.length) < 4) false else {
                var previous = IntArray(b.length + 1) { it }
                a.forEachIndexed { i, char ->
                    val current = IntArray(b.length + 1)
                    current[0] = i + 1
                    b.forEachIndexed { j, other -> current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                        previous[j] + if (char == other) 0 else 1) }
                    previous = current
                }
                1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length) >= 0.78
            }
        }
    }
}
