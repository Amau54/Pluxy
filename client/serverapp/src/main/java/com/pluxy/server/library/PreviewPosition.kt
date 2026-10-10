package com.pluxy.server.library

object PreviewPosition {
    fun second(position: Double, duration: Double): Long {
        require(position.isFinite() && position in 0.0..604_800.0) { "Position invalide" }
        val bounded = if (duration.isFinite() && duration > 0) position.coerceAtMost((duration - .1).coerceAtLeast(0.0)) else position
        return bounded.toLong() / 10 * 10
    }
}
