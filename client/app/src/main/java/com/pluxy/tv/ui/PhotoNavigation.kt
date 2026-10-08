package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

internal data class PhotoFolderPage(val key: String, val label: String, val photos: List<MediaItem>)

/** Same-named folders from different sources keep their own identity. */
internal fun photoFolderPages(ordered: List<MediaItem>): List<PhotoFolderPage> = ordered
    .filter { it.kind == "photo" && it.available }
    .groupBy(LibraryOrganizer::photoAlbumKey)
    .map { (key, items) ->
        val first = items.first()
        val path = first.relativePath?.replace('\\', '/')?.substringBeforeLast('/', "").orEmpty()
        val label = listOfNotNull(first.sourceName?.takeIf(String::isNotBlank),
            path.takeIf(String::isNotBlank) ?: first.folder?.takeIf(String::isNotBlank))
            .distinct().joinToString(" / ").ifBlank { "Dossier principal" }
        PhotoFolderPage(key, label, items)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { page: PhotoFolderPage -> page.label }.thenBy { it.key })

internal fun wrappedPhotoIndex(index: Int, size: Int): Int = ((index % size) + size) % size

internal fun nearbyPhotoIds(pages: List<PhotoFolderPage>, photos: List<MediaItem>, index: Int): List<String> {
    val current = photos.getOrNull(index) ?: return emptyList()
    val nearby = if (photos.size > 1) listOf(1, -1, 2, -2).map {
        photos[wrappedPhotoIndex(index + it, photos.size)].id
    } else emptyList()
    val page = pages.indexOfFirst { it.key == LibraryOrganizer.photoAlbumKey(current) }
    val folders = if (page >= 0 && pages.size > 1) listOf(1, -1).mapNotNull {
        pages[wrappedPhotoIndex(page + it, pages.size)].photos.firstOrNull()?.id
    } else emptyList()
    return (nearby + folders).filter { it != current.id }.distinct().take(6)
}

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
