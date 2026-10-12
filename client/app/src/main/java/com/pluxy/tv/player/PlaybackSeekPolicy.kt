package com.pluxy.tv.player

/** A remux pipe starts at a requested time; its player position starts at zero. */
object PlaybackSeekPolicy {
    fun isRemux(url: String): Boolean = url.substringBefore('?').let {
        it.startsWith("/stream/remux/") || it.startsWith("/stream/convert/")
    }
    fun absolutePosition(offsetMs: Long, playerPositionMs: Long): Long =
        offsetMs.coerceAtLeast(0) + playerPositionMs.coerceAtLeast(0)
}
