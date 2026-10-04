package com.pluxy.tv.ui

/** L'identité de la carte évite un saut lorsque des ajouts décalent ses indices. */
internal data class ShelfScrollAnchor(val rowKey: String, val position: Int, val offsetPx: Int)
internal data class ShelfScrollPosition(val position: Int, val offsetPx: Int)

internal fun resolveShelfScrollAnchor(anchor: ShelfScrollAnchor?, rowKeys: List<String>): ShelfScrollPosition? {
    if (anchor == null || rowKeys.isEmpty()) return null
    val exact = rowKeys.indexOf(anchor.rowKey)
    return ShelfScrollPosition(
        if (exact >= 0) exact else anchor.position.coerceIn(rowKeys.indices),
        anchor.offsetPx,
    )
}

internal fun movedShelfOrder(order: List<String>, shelfId: String, direction: Int): List<String> {
    if (direction != -1 && direction != 1) return order
    val source = order.indexOf(shelfId)
    val target = source + direction
    if (source < 0 || target !in order.indices) return order
    return order.toMutableList().apply { add(target, removeAt(source)) }
}
