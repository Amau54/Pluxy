package com.pluxy.server.library

/** Opening text cards only: no chapters, network service or fixed skip duration. */
internal object OpeningSignals {
    data class Frame(val text: Boolean = false, val black: Boolean = false, val signature: Set<Int> = emptySet())

    fun frame(pixels: IntArray): Frame {
        if (pixels.size != 128 * 72) return Frame()
        val bright = pixels.indices.filterTo(mutableSetOf()) { pixels[it] > 80 }
        val dark = pixels.count { it < 44 }.toDouble() / pixels.size
        val black = dark >= .98 && bright.size < 10
        if (dark < .80 || bright.size !in 55..1658) return Frame(black = black)
        if (bright.count { it < 128 * 54 } < bright.size * .65) return Frame()
        val rows = BooleanArray(72) { y -> (0 until 128).count { x -> y * 128 + x in bright } >= 3 }
        if (rows.indices.count { rows[it] && (it == 0 || !rows[it - 1]) } < 2) return Frame()
        val remaining = bright.toMutableSet()
        var glyphs = 0
        while (remaining.isNotEmpty()) {
            val seed = remaining.first()
            remaining.remove(seed)
            val stack = java.util.ArrayDeque<Int>().apply { add(seed) }
            var count = 0
            var minX = 128; var maxX = 0; var minY = 72; var maxY = 0
            while (stack.isNotEmpty()) {
                val pixel = stack.removeLast()
                val x = pixel % 128; val y = pixel / 128
                count++
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                val neighbors = intArrayOf(if (x > 0) pixel - 1 else -1, if (x < 127) pixel + 1 else -1,
                    if (y > 0) pixel - 128 else -1, if (y < 71) pixel + 128 else -1)
                for (neighbor in neighbors) if (remaining.remove(neighbor)) stack.add(neighbor)
            }
            if (count >= 2 && maxX - minX + 1 <= 24 && maxY - minY + 1 in 2..14) glyphs++
        }
        if (glyphs < 12) return Frame()
        return Frame(text = true, signature = bright.mapTo(mutableSetOf()) { (it / 128 / 2) * 64 + it % 128 / 2 })
    }

    fun interval(frames: List<Frame>, step: Int = 2): Pair<Int, Int>? {
        var start = -1
        var last = -1
        val signatures = mutableListOf<Set<Int>>()
        val counts = mutableListOf<Int>()
        var textCount = 0
        fun reset() { start = -1; signatures.clear(); counts.clear(); textCount = 0 }
        for ((index, frame) in frames.withIndex()) {
            if (frame.text) {
                if (start < 0) {
                    if (index == 0 || !frames[index - 1].black) continue
                    start = index
                }
                if ((index - start) * step > 180) { reset(); continue }
                last = index
                textCount++
                val match = signatures.indexOfFirst { signature ->
                    signature.count { it !in frame.signature } + frame.signature.count { it !in signature } <= 12
                }
                if (match < 0) { signatures += frame.signature; counts += 1 } else counts[match]++
            } else if (start >= 0) {
                if (frame.black && (index - last) * step <= 4) {
                    if ((last - start) * step >= 20 && textCount.toDouble() / (last - start + 1) >= .65 && counts.count { it >= 2 } >= 3)
                        return start * step to last * step
                    continue
                }
                reset()
            }
        }
        return null
    }
}
