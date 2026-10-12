package com.pluxy.tv.player

import com.pluxy.tv.api.ClientCapabilities
import com.pluxy.tv.api.ConversionProfile
import com.pluxy.tv.api.MediaItem

object ConversionPolicy {
    fun compatible(profiles: List<ConversionProfile>, item: MediaItem, caps: ClientCapabilities): List<ConversionProfile> =
        profiles.filter { profile ->
            val sourceIs4k = (item.width ?: 0) > 1920 || (item.height ?: 0) > 1080
            (profile.height <= caps.maxVideoHeight) && (sourceIs4k || profile.height <= 1080) &&
                when (profile.codec) {
                    "hevc" -> caps.supportsHevc && (!item.isHdr || (caps.supportsHevc10bit && caps.supportsHdr))
                    "h264" -> caps.supportsH264
                    else -> false
                }
        }

    fun adaptive(strategy: PlaybackStrategy, quality: String): Boolean =
        strategy == PlaybackStrategy.AUTO || (strategy == PlaybackStrategy.COMPATIBILITY && quality == "auto")
}
