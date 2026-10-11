package com.pluxy.tv.ui

import android.widget.ImageView
import coil.load
import coil.dispose
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.LanUrlPolicy
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.LanMediaImages

object EpisodeArtwork {
    fun load(view: ImageView, item: MediaItem, serverUrl: String = PluxyApplication.serverBaseUrl(view.context)) {
        val token = Any()
        view.tag = token
        val remote = LanUrlPolicy.safeTmdbImage(item.episodeStillUrl)
        val local = item.thumbnailUrl?.let { runCatching { LanUrlPolicy.resolveSameServer(serverUrl, it) }.getOrNull() }
        val loader = LanMediaImages.loader(view.context)
        fun fallback() {
            if (local == null) {
                view.dispose(); view.setImageResource(R.drawable.ic_episode_placeholder)
            } else view.load(local, loader) {
                placeholder(R.drawable.ic_episode_placeholder); error(R.drawable.ic_episode_placeholder); crossfade(true)
            }
        }
        view.contentDescription = null
        if (remote == null) fallback()
        else view.load(remote) {
            placeholder(R.drawable.ic_episode_placeholder); crossfade(true)
            listener(onError = { _, _ -> view.post {
                // An old request must not replace the artwork of a recycled episode row.
                if (view.tag === token) fallback()
            } })
        }
    }
}
