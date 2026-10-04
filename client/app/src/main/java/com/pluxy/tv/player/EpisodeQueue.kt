package com.pluxy.tv.player

import com.pluxy.tv.api.MediaItem
import java.util.Locale

/** Séquence locale et déterministe d'une série, sans dépendre des titres affichés. */
object EpisodeQueue {
    fun next(current: MediaItem, library: List<MediaItem>): MediaItem? {
        if (current.kind != "series") return null
        val key = current.seriesKey?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        val name = (current.seriesName ?: current.title).trim().lowercase(Locale.ROOT)
        val episodes = library.asSequence()
            .filter { it.kind == "series" }
            .filter {
                if (key != null) it.seriesKey?.trim()?.lowercase(Locale.ROOT) == key
                else (it.seriesName ?: it.title).trim().lowercase(Locale.ROOT) == name
            }
            .sortedWith(
                compareBy<MediaItem>(
                    { it.season ?: Int.MAX_VALUE },
                    { it.episode ?: Int.MAX_VALUE },
                    { it.title.lowercase(Locale.ROOT) },
                    { it.id },
                ),
            )
            .toList()
        val index = episodes.indexOfFirst { it.id == current.id }
        return episodes.getOrNull(index + 1).takeIf { index >= 0 }
    }
}
