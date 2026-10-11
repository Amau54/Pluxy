package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem

/** Racines distinctes, sous-dossiers à la suite, photos visibles sans ouvrir un album. */
object PhotoFolderTree {
    private class Node(val id: String, val name: String, val depth: Int) {
        val children = linkedMapOf<String, Node>()
        val photos = mutableListOf<MediaItem>()
        var count = 0
    }

    fun folderKey(item: MediaItem): String {
        val legacy = item.folder.orEmpty().replace(" / ", "/").replace('\\', '/')
        val source = item.sourceId ?: "legacy:${item.folderId ?: legacy.ifBlank { "photos" }}"
        val folder = if (item.sourceId == null) "" else item.relativePath?.replace('\\', '/')?.substringBeforeLast('/', "").orEmpty()
        return "photo-tree:$source" + folder.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("", prefix = "") { "/$it" }
    }

    fun rows(items: List<MediaItem>, collapsed: Set<String> = emptySet(),
             query: TvLibraryContent.Query = TvLibraryContent.Query("photo", sort = "title")): List<LibRow> {
        val roots = linkedMapOf<String, Node>()
        items.forEach { item ->
            val relative = item.relativePath?.replace('\\', '/')
            val legacy = item.folder.orEmpty().replace(" / ", "/").replace('\\', '/')
            val source = item.sourceId ?: "legacy:${item.folderId ?: legacy.ifBlank { "photos" }}"
            val name = item.sourceName?.takeIf(String::isNotBlank)
                ?: legacy.ifBlank { "Photos" }
            var node = roots.getOrPut(source) { Node("photo-tree:$source", name, 0) }
            node.count += item.groupCount.coerceAtLeast(1)
            // Un ancien serveur n'expose pas l'identité de la racine : garder ses
            // dossiers séparés évite de fusionner deux albums homonymes.
            val folder = if (item.sourceId == null) "" else relative?.substringBeforeLast('/', "") ?: ""
            folder.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.forEach { part ->
                val parent = node
                node = parent.children.getOrPut(part) { Node("${parent.id}/$part", part, parent.depth + 1) }
                node.count += item.groupCount.coerceAtLeast(1)
            }
            node.photos += item
        }
        return buildList {
            fun visit(node: Node) {
                val expanded = node.id !in collapsed
                add(LibRow.Header(node.id, "${node.name}  ·  ${node.count} ${if (node.count == 1) "photo" else "photos"}",
                    collapsible = true, expanded = expanded, depth = node.depth))
                if (!expanded) return
                node.children.values.sortedBy { LibraryOrganizer.searchKey(it.name) }.forEach(::visit)
                if (node.children.isNotEmpty() && node.photos.isNotEmpty()) {
                    add(LibRow.Header("${node.id}:files", "Images de ${node.name}", depth = node.depth))
                }
                addAll(TvLibraryContent.sortMedia(node.photos, query).map { LibRow.MediaRow(it) })
            }
            roots.values.sortedBy { LibraryOrganizer.searchKey(it.name) }.forEach(::visit)
        }
    }
}
