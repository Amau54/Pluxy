package com.pluxy.server.library

import java.util.ArrayDeque
import kotlin.math.*

/** One-second overlapping spectra keep the same music recognisable after a small time shift. */
internal object AudioSignals {
    const val STEP = .256
    const val SPAN = 1.024
    private const val SIZE = 512
    private val reverse = IntArray(SIZE) { Integer.reverse(it) ushr 23 }
    private val hann = DoubleArray(SIZE) { .5 - .5 * cos(2 * Math.PI * it / (SIZE - 1)) }
    private val bands = Array(32) { index ->
        (round((100 + index * 100) * SIZE / 8000.0).toInt() until round((200 + index * 100) * SIZE / 8000.0).toInt())
    }
    private val roots = (1..9).associate { power ->
        val size = 1 shl power
        size to Pair(DoubleArray(size / 2) { cos(-2 * Math.PI * it / size) }, DoubleArray(size / 2) { sin(-2 * Math.PI * it / size) })
    }

    fun fingerprints(samples: ShortArray): IntArray {
        val history = ArrayDeque<DoubleArray>()
        val powers = ArrayDeque<Double>()
        val totals = DoubleArray(32)
        val result = ArrayList<Int>()
        var totalPower = 0.0
        var index = 0
        var offset = 0
        while (offset + SIZE <= samples.size) {
            val real = DoubleArray(SIZE) { samples[offset + reverse[it]] * hann[reverse[it]] }
            val imaginary = DoubleArray(SIZE)
            for ((size, root) in roots) {
                val half = size / 2
                for (base in 0 until SIZE step size) for (j in 0 until half) {
                    val evenR = real[base + j]; val evenI = imaginary[base + j]
                    val at = base + j + half
                    val oddR = real[at] * root.first[j] - imaginary[at] * root.second[j]
                    val oddI = real[at] * root.second[j] + imaginary[at] * root.first[j]
                    real[base + j] = evenR + oddR; imaginary[base + j] = evenI + oddI
                    real[at] = evenR - oddR; imaginary[at] = evenI - oddI
                }
            }
            val spectrum = DoubleArray(SIZE / 2) { real[it] * real[it] + imaginary[it] * imaginary[it] }
            val energies = DoubleArray(32) { band -> bands[band].sumOf { spectrum[it] } / bands[band].count() }
            var power = 0.0
            for (i in offset until offset + SIZE) power += samples[i].toDouble() * samples[i]
            power /= SIZE
            history.add(energies); powers.add(power); totalPower += power
            for (band in totals.indices) totals[band] += energies[band]
            if (history.size > 16) {
                totalPower -= powers.removeFirst()
                val old = history.removeFirst()
                for (band in totals.indices) totals[band] -= old[band]
            }
            if (history.size == 16 && (index + 1 - 16) % 4 == 0) {
                if (totalPower / 16 < 36) result.add(-1) else {
                    val floor = totals.maxOrNull()!! * .0001
                    var hash = 0
                    for (band in 0 until 31) if (totals[band + 1] > totals[band] * 1.05 && totals[band + 1] > floor)
                        hash = hash or (1 shl band)
                    result.add(hash)
                }
            }
            offset += SIZE; index++
        }
        return result.toIntArray()
    }

    data class Match(val start: Double, val end: Double, val otherStart: Double, val distance: Double, val coverage: Double)

    fun match(left: IntArray, right: IntArray, step: Double = STEP, span: Double = SPAN): Match? {
        if (left.isEmpty() || right.isEmpty()) return null
        val votes = HashMap<Int, Int>()
        for (i in left.indices step 3) {
            val value = left[i]
            if (value < 0) continue
            for (j in right.indices) if (right[j] >= 0 && Integer.bitCount(value xor right[j]) <= 4)
                votes[j - i] = (votes[j - i] ?: 0) + 1
        }
        var best: Match? = null
        for (lag in votes.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).take(16).map { it.key }) {
            val first = max(0, -lag); val stop = min(left.size, right.size - lag)
            var start = -1; var last = -1; var gaps = 0
            val distances = ArrayList<Int>()
            fun candidate() {
                if (start < 0 || last < start) return
                val count = last - start + 1
                val duration = count * step
                val portion = distances.take(count)
                val good = portion.count { it <= 7 }.toDouble() / count
                val mean = portion.average()
                if (duration < 8 || duration > 180 || duration >= min(left.size, right.size) * step * .65) return
                if (last >= stop - 2 || good < .85 || mean > 5) return
                if (left.slice(start..last).distinct().size < max(8, min(24, count / 3))) return
                val result = Match(start * step + span, last * step, (start + lag) * step + span, mean, good)
                if (result.end - result.start < 6) return
                if (best == null || result.end - result.start > best!!.end - best!!.start) best = result
            }
            for (i in first until stop) {
                val distance = if (left[i] >= 0 && right[i + lag] >= 0) Integer.bitCount(left[i] xor right[i + lag]) else 31
                if (distance <= 7) {
                    if (start < 0) start = i
                    distances.add(distance); last = i; gaps = 0
                } else if (start >= 0) {
                    distances.add(distance); gaps++
                    if (gaps * step > .8) { candidate(); start = -1; distances.clear(); gaps = 0 }
                }
            }
            candidate()
        }
        return best
    }
}
