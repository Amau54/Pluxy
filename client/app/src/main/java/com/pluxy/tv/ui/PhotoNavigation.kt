package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

internal fun validPhotoFolder(items: List<MediaItem>, selected: String): String =
    selected.takeIf { folder ->
        folder.isBlank() || items.any { it.kind == "photo" && TvLibraryContent.photoFolderLabel(it) == folder }
    }.orEmpty()

internal fun restoredPhotoIndex(items: List<MediaItem>, mediaId: String?): Int =
    items.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)

/** Loading/failure must never start the viewing interval or replace a pending image. */
internal fun shouldAdvanceSlideshow(
    requested: Boolean,
    started: Boolean,
    displayed: Boolean,
    count: Int,
): Boolean = requested && started && displayed && count > 1
