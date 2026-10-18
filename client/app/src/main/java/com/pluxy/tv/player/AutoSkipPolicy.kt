package com.pluxy.tv.player

/** One automatic jump per segment kind and media, including after a player rebuild. */
class AutoSkipPolicy {
    private var owner = ""
    private val handled = mutableSetOf<String>()

    fun mark(itemId: String, kind: String) {
        if (owner != itemId) { owner = itemId; handled.clear() }
        handled += kind
    }

    fun target(itemId: String, segment: SkipSegment?, enabled: Boolean, canSkip: Boolean): Long? {
        if (owner != itemId) { owner = itemId; handled.clear() }
        if (!enabled || !canSkip || segment == null || segment.kind !in setOf("intro", "credits") ||
            segment.startMs < 0 || segment.endMs <= segment.startMs || segment.kind in handled) return null
        mark(itemId, segment.kind)
        return segment.endMs
    }

    companion object {
        const val PROBE_ATTEMPTS = 80
        fun probeDelayMs(attempt: Int): Long = when {
            attempt < 10 -> 6_000L
            attempt < 30 -> 15_000L
            else -> 30_000L
        }
    }
}
