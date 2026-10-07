package com.pluxy.server.library

internal fun LibrarySource.identity(): String = "${kind.name}:$treeUri"

/** Absence only means deletion after a complete scan of the entry's source. */
internal object ScanMergePolicy {
    fun merge(previous: List<MediaEntry>, found: List<MediaEntry>, sources: List<LibrarySource>, incomplete: Set<String>, limit: Int): List<MediaEntry> {
        val fresh = found.associateBy { it.id }
        val selected = sources.associateBy { it.identity() }
        val result = linkedMapOf<String, MediaEntry>()
        for (old in previous) {
            val replacement = fresh[old.id]
            if (replacement != null) result[old.id] = replacement.copy(addedAtMs = old.addedAtMs) else {
                val source = old.sourceKey?.let(selected::get) ?: sources.firstOrNull {
                    val kindMatches = when (it.kind) {
                        LibrarySourceKind.VIDEO -> old.kind == "movie" || old.kind == "series"
                        else -> old.kind == it.kind.publicKind
                    }
                    kindMatches && if (it.isAutomatic) old.uri.startsWith("content://media/")
                    else old.uri.startsWith(it.treeUri.trimEnd('/') + "/")
                }
                if (source != null && source.identity() in incomplete) {
                    result[old.id] = old.copy(sourceKey = source.identity(), available = false)
                }
            }
            if (result.size >= limit) break
        }
        for (item in found) {
            if (result.size >= limit) break
            if (item.id !in result) result[item.id] = item
        }
        return result.values.toList()
    }
}

internal fun audioNumber(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
