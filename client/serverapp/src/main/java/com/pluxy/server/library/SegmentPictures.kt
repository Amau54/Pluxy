package com.pluxy.server.library

import kotlin.math.*

internal object SegmentPictures {
    fun same(left: IntArray, right: IntArray): Boolean {
        if (left.size != 9216 || right.size != 9216) return false
        fun cells(frame: IntArray) = DoubleArray(128) { index ->
            val x = index % 16; val y = index / 16
            var sum = 0.0
            for (dy in 0 until 7) for (dx in 0 until 7) sum += frame[(8 + y * 7 + dy) * 128 + 8 + x * 7 + dx]
            sum / 49
        }
        val a = cells(left); val b = cells(right)
        fun deviation(values: DoubleArray): Double { val mean = values.average(); return sqrt(values.sumOf { (it - mean).pow(2) } / values.size) }
        if (min(deviation(a), deviation(b)) < 6) return false
        var different = 0
        for (y in 0 until 8) for (x in 0 until 15) {
            if ((a[y * 16 + x + 1] > a[y * 16 + x]) != (b[y * 16 + x + 1] > b[y * 16 + x])) different++
        }
        return different <= 18 && a.indices.sumOf { abs(a[it] - b[it]) } / a.size <= 30
    }
}
