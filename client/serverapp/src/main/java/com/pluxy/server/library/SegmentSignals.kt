package com.pluxy.server.library

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Matching local audio only; silence and duplicate episodes never count as an intro. */
object SegmentSignals {
    private val coefficients = DoubleArray(32) { 2 * cos(2 * Math.PI * (90 * (3000.0 / 90).pow(it / 31.0)) / 8000) }
    fun fingerprints(samples: ShortArray): IntArray = IntArray(samples.size / 8000) { second ->
        val offset = second * 8000
        var power = 0.0
        for (i in 0 until 8000) power += samples[offset + i].toDouble().pow(2)
        if (power / 8000 < 36) -1 else {
            val energies = DoubleArray(32)
            for (phase in intArrayOf(0, 2000, 4000, 6000)) for (band in coefficients.indices) {
                var previous = 0.0
                var older = 0.0
                for (i in 0 until 256) {
                    val current = samples[offset + phase + i] + coefficients[band] * previous - older
                    older = previous; previous = current
                }
                energies[band] += max(0.0, previous * previous + older * older - coefficients[band] * previous * older)
            }
            var hash = 0
            val floor = energies.maxOrNull()!! * 0.001
            for (i in 0 until 31) if (energies[i + 1] > energies[i] * 1.12 && energies[i + 1] > floor) hash = hash or (1 shl i)
            hash
        }
    }
    fun repeatedIntro(left: IntArray, right: IntArray): Pair<Int, Int>? {
        var previous = IntArray(right.size + 1)
        var bestStart = 0
        var bestLength = 0
        var otherStart = 0
        for (i in left.indices) {
            val current = IntArray(right.size + 1)
            for (j in right.indices) if (left[i] >= 0 && right[j] >= 0 && Integer.bitCount(left[i] xor right[j]) <= 5) {
                current[j + 1] = previous[j] + 1
                if (current[j + 1] > bestLength) { bestLength = current[j + 1]; bestStart = i + 1 - bestLength; otherStart = j + 1 - bestLength }
            }
            previous = current
        }
        if (bestLength !in 25..180 || bestLength >= min(left.size, right.size) * 0.65) return null
        if (left.slice(bestStart until bestStart + bestLength).distinct().size < 12) return null
        if (bestStart + bestLength >= left.size - 2 || otherStart + bestLength >= right.size - 2) return null
        return bestStart to bestStart + bestLength - 1
    }
    fun creditFrame(frame: IntArray, width: Int = 128, height: Int = 72): Boolean {
        if (frame.size != width * height) return false
        val dark = frame.count { it < 44 }.toDouble() / frame.size
        val bright = frame.count { it > 155 }
        if (dark < 0.68 || bright.toDouble() / frame.size !in 0.005.. 0.23) return false
        var groups = 0
        var previous = false
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) if (frame[y * width + x] > 155) count++
            val active = count >= 3
            if (active && !previous) groups++
            previous = active
        }
        return groups >= 4 && frame.take(width * 54).count { it > 155 } > bright * 0.35
    }
    fun creditInterval(flags: List<Boolean>, step: Int = 2): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var start = -1
        var last = 0
        var gaps = 0
        for ((index, flag) in (flags + listOf(false, false, false)).withIndex()) {
            if (flag) { if (start < 0) start = index; last = index; gaps = 0 }
            else if (start >= 0 && ++gaps > 1) {
                if (last - start + 1 >= 12 && flags.subList(start, last + 1).count { it }.toDouble() / (last - start + 1) >= 0.85) {
                    val candidate = start * step to last * step
                    if (best == null || candidate.second - candidate.first > best.second - best.first) best = candidate
                }
                start = -1
            }
        }
        return best
    }
}
