package com.pluxy.server.library


/** Matching local audio only; silence and duplicate episodes never count as an intro. */
object SegmentSignals {
    fun fingerprints(samples: ShortArray): IntArray = AudioSignals.fingerprints(samples)
    fun repeatedIntro(left: IntArray, right: IntArray, step: Double = AudioSignals.STEP, span: Double = AudioSignals.SPAN): Pair<Double, Double>? =
        AudioSignals.match(left, right, step, span)?.let { it.start to it.end }
    fun creditFrame(frame: IntArray, width: Int = 128, height: Int = 72): Boolean {
        if (frame.size != width * height) return false
        val histogram = IntArray(256)
        for (value in frame) histogram[value.coerceIn(0, 255)]++
        fun percentile(portion: Double): Int {
            var total = 0
            for (level in histogram.indices) { total += histogram[level]; if (total >= frame.size * portion) return level }
            return 255
        }
        val background = percentile(.5); val peak = percentile(.995)
        if (peak - background < 12) return false
        val threshold = background + maxOf(12.0, (peak - background) * .65)
        val dark = frame.count { it <= background + (peak - background) * .2 }.toDouble() / frame.size
        val bright = frame.count { it > threshold }
        if (dark < 0.68 || bright.toDouble() / frame.size !in 0.005.. 0.23) return false
        var groups = 0
        var previous = false
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width - 1) if (frame[y * width + x] > threshold && frame[y * width + x + 1] > threshold) count++
            val active = count >= 2
            if (active && !previous) groups++
            previous = active
        }
        return groups >= 4 && frame.take(width * (height * .75).toInt()).count { it > threshold } > bright * 0.35
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
