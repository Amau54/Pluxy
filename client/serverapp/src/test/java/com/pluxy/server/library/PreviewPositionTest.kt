package com.pluxy.server.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewPositionTest {
    @Test fun `nearby destinations share one low resolution capture`() {
        assertEquals(10L, PreviewPosition.second(19.9, 60.0))
        assertEquals(10L, PreviewPosition.second(10.0, 60.0))
        assertEquals(50L, PreviewPosition.second(60.0, 60.0))
        assertEquals(0L, PreviewPosition.second(2.0, 1.0))
    }
    @Test fun `invalid positions never reach the media retriever`() {
        for (position in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 604_801.0)) {
            assertThrows(IllegalArgumentException::class.java) { PreviewPosition.second(position, 60.0) }
        }
    }
}
