package com.pluxy.tv.api

/** Reject malformed/looping pagination instead of silently returning a partial library. */
internal suspend fun collectLibraryPages(
    load: suspend (offset: Int, revision: String?) -> LibraryPage,
): List<MediaItem> {
    val items = ArrayList<MediaItem>()
    val ids = HashSet<String>()
    var revision: String? = null
    var total: Int? = null
    var offset = 0
    while (true) {
        val page = load(offset, revision)
        if (page.total !in 0..100_000 || page.items.size > 500 || page.revision.length !in 1..256) {
            throw ApiException(413, "Pagination de bibliothèque invalide")
        }
        if (revision != null && (page.revision != revision || page.total != total)) {
            throw ApiException(409, "La bibliothèque a changé pendant le chargement")
        }
        revision = page.revision
        total = page.total
        for (item in page.items) {
            if (!ids.add(item.id)) throw ApiException(409, "Page de bibliothèque incohérente")
            items.add(item)
        }
        val next = page.nextOffset
        if (next == null) {
            if (items.size != page.total) throw ApiException(409, "Bibliothèque incomplète")
            return items
        }
        if (page.items.isEmpty() || next != offset + page.items.size || next >= page.total) {
            throw ApiException(409, "Pagination de bibliothèque incohérente")
        }
        offset = next
    }
}
