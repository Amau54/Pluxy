package com.pluxy.server.http

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1

    companion object {
        /** Range is GET-only; an outdated/weak If-Range validator requires the full file. */
        fun forRequest(method: String, headers: Map<String, String>, totalSize: Long, etag: String): ByteRange? {
            if (method != "GET") return null
            val header = headers["range"] ?: return null
            if (headers["if-range"]?.trim()?.let { it != etag || it.startsWith("W/") } == true) return null
            if (!header.substringBefore('=').trim().equals("bytes", ignoreCase = true)) return null
            return parse(header, totalSize)
        }

        /** Une seule plage HTTP est nécessaire à Media3. Les multi-ranges sont refusées. */
        fun parse(header: String?, totalSize: Long): ByteRange? {
            if (header.isNullOrBlank()) return null
            require(totalSize > 0) { "Taille inconnue" }
            val normalized = header.trim()
            require(!normalized.contains(',')) { "Multi-range non pris en charge" }
            val match = Regex("(?i)^bytes=(\\d*)-(\\d*)$").matchEntire(normalized)
                ?: throw IllegalArgumentException("Range invalide")
            val first = match.groupValues[1]
            val second = match.groupValues[2]
            require(first.isNotBlank() || second.isNotBlank()) { "Range vide" }

            if (first.isBlank()) {
                val suffixLength = second.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("Suffixe invalide")
                val length = suffixLength.coerceAtMost(totalSize)
                return ByteRange(totalSize - length, totalSize - 1)
            }

            val start = first.toLongOrNull() ?: throw IllegalArgumentException("Début invalide")
            require(start in 0 until totalSize) { "Range hors limites" }
            val requestedEnd = if (second.isBlank()) totalSize - 1
            else second.toLongOrNull() ?: throw IllegalArgumentException("Fin invalide")
            require(requestedEnd >= start) { "Range inversé" }
            return ByteRange(start, requestedEnd.coerceAtMost(totalSize - 1))
        }
    }
}
