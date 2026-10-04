package com.pluxy.server.library

import java.util.UUID

/** Publish list and lookup index together so readers always see the same completed scan. */
internal class MediaCatalog(items: List<MediaEntry>) {
    val revision: String = UUID.randomUUID().toString()
    val items: List<MediaEntry> = items.toList()
    private val byId: Map<String, MediaEntry> = this.items.associateBy(MediaEntry::id)

    fun get(id: String): MediaEntry? = byId[id]

    fun page(limit: Int, offset: Int, requestedRevision: String?): MediaPage {
        require(limit in 1..500 && offset >= 0) { "Pagination invalide" }
        if (requestedRevision != null && requestedRevision != revision) throw CatalogRevisionChanged()
        val start = offset.coerceAtMost(items.size)
        val end = (start.toLong() + limit).coerceAtMost(items.size.toLong()).toInt()
        return MediaPage(items.subList(start, end), items.size, revision, end.takeIf { it < items.size })
    }
}

data class MediaPage(val items: List<MediaEntry>, val total: Int, val revision: String, val nextOffset: Int?)
class CatalogRevisionChanged : IllegalStateException("La bibliothèque a changé. Recommencez la pagination.")
