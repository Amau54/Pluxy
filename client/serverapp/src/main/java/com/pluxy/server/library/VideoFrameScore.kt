package com.pluxy.server.library

import kotlin.math.sqrt

internal object VideoFrameScore {
    fun score(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        var squared = 0.0
        pixels.forEach { rgb ->
            val luma = ((rgb shr 16 and 255) * 0.299 + (rgb shr 8 and 255) * 0.587 + (rgb and 255) * 0.114)
            sum += luma
            squared += luma * luma
        }
        val mean = sum / pixels.size
        val contrast = sqrt((squared / pixels.size - mean * mean).coerceAtLeast(0.0))
        return minOf(1.0, mean / 32, (255 - mean) / 32).coerceAtLeast(0.0) * (contrast + 1)
    }
}
