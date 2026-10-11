package com.pluxy.tv.ui

import com.pluxy.tv.api.LanUrlPolicy
import java.net.URI
import java.net.URLDecoder

internal data class TrailerLinks(val web: String, val video: String) {
    companion object {
        fun from(value: String?): TrailerLinks? = runCatching {
            val uri = URI(LanUrlPolicy.safeYoutubeTrailer(value) ?: return null)
            if (uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
            val parts = uri.path.orEmpty().trim('/').split('/')
            val key = if (uri.host.equals("youtu.be", true)) {
                parts.singleOrNull()
            } else if (parts.firstOrNull() in listOf("embed", "shorts", "live") && parts.size == 2) {
                parts[1]
            } else if (uri.path == "/watch") {
                uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }
                    ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
            } else null
            if (key == null || !key.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
            TrailerLinks("https://www.youtube.com/watch?v=$key", "vnd.youtube://$key")
        }.getOrNull()
    }
}
