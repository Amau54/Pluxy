package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

/** Available album tracks plus the explicit selection, ordered by metadata then legacy labels. */
internal fun orderedAlbumTracks(items: List<MediaItem>, reference: MediaItem): List<MediaItem> =
    items.filter { LibraryOrganizer.sameAudioAlbum(reference, it) && (it.available || it.id == reference.id) }.sortedWith(
        compareBy<MediaItem> { it.discNumber?.takeIf { number -> number > 0 } ?: 1 }
            .thenBy { it.trackNumber?.takeIf { number -> number > 0 } ?: filenameTrackNumber(it.path) ?: Int.MAX_VALUE }
            .thenBy { LibraryOrganizer.searchKey(it.title) }
            .thenBy { it.path }
            .thenBy { it.id },
    )

private fun filenameTrackNumber(path: String): Int? =
    Regex("^([0-9]{1,3})(?:[ ._-]|$)")
        .find(path.substringAfterLast('/').substringAfterLast('\\'))
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
