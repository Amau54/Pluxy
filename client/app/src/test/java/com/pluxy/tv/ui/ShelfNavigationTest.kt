package com.pluxy.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfNavigationTest {
    @Test fun insertionBeforeTheVisibleCardPreservesItsIdentityAndOffset() {
        assertEquals(ShelfScrollPosition(3, -37), resolveShelfScrollAnchor(
            ShelfScrollAnchor("c", 2, -37), listOf("new", "a", "b", "c", "d"),
        ))
    }

    @Test fun reorderingUsesTheCardKeyInsteadOfTheOldIndex() {
        assertEquals(ShelfScrollPosition(0, 12), resolveShelfScrollAnchor(
            ShelfScrollAnchor("c", 2, 12), listOf("c", "a", "b"),
        ))
    }

    @Test fun deletingTheAnchorKeepsTheNearestRemainingPosition() {
        assertEquals(ShelfScrollPosition(1, -8), resolveShelfScrollAnchor(
            ShelfScrollAnchor("deleted", 4, -8), listOf("a", "b"),
        ))
    }

    @Test fun anUnvisitedOrEmptyShelfDoesNotRequestAnAnchor() {
        assertNull(resolveShelfScrollAnchor(null, listOf("a")))
        assertNull(resolveShelfScrollAnchor(ShelfScrollAnchor("a", 0, 0), emptyList()))
    }

    @Test fun movingARubricRetainsEveryRubricAndDoesNotMutateTheInput() {
        val original = listOf("continue", "recent", "movies", "series")
        assertEquals(listOf("continue", "movies", "recent", "series"), movedShelfOrder(original, "movies", -1))
        assertEquals(listOf("continue", "recent", "series", "movies"), movedShelfOrder(original, "movies", 1))
        assertEquals(listOf("continue", "recent", "movies", "series"), original)
    }

    @Test fun boundaryAndStaleRubricActionsAreHarmless() {
        val order = listOf("continue", "movies")
        assertEquals(order, movedShelfOrder(order, "continue", -1))
        assertEquals(order, movedShelfOrder(order, "movies", 1))
        assertEquals(order, movedShelfOrder(order, "removed", 1))
        assertEquals(order, movedShelfOrder(order, "movies", -2))
    }
}
