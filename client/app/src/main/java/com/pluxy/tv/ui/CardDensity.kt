package com.pluxy.tv.ui

enum class CardDensity(val key: String, val label: String, val scale: Float) {
    COMPACT("compact", "Compact", 0.82f),
    BALANCED("balanced", "Équilibré", 1f),
    COMFORT("comfort", "Confort", 1.25f);

    fun columns(availableWidth: Float, baseCardWidth: Float, gap: Float = 12f): Int =
        (availableWidth / (baseCardWidth * scale + gap)).toInt().coerceIn(2, 8)

    companion object {
        fun fromStored(value: String?, legacyCompact: Boolean? = null): CardDensity =
            entries.firstOrNull { it.key == value }
                ?: if (legacyCompact == false) COMFORT else BALANCED
    }
}
