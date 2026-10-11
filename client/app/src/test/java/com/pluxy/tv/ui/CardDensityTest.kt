package com.pluxy.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDensityTest {
    @Test fun previousDefaultKeepsItsCardSize() {
        assertEquals(CardDensity.BALANCED, CardDensity.fromStored(null))
        assertEquals(CardDensity.BALANCED, CardDensity.fromStored(null, true))
        assertEquals(CardDensity.COMFORT, CardDensity.fromStored(null, false))
        assertEquals(CardDensity.COMPACT, CardDensity.fromStored("compact", true))
        assertEquals(CardDensity.BALANCED, CardDensity.fromStored("balanced", false))
        assertEquals(CardDensity.COMFORT, CardDensity.fromStored("unknown", false))
    }

    @Test fun televisionOffersThreeDistinctSizesWithoutChangingTheOldDefault() {
        val columns = CardDensity.entries.map { it.columns(720f, 108f) }
        assertEquals(listOf(7, 6, 4), columns)
        assertEquals(6, CardDensity.BALANCED.columns(720f, 108f))
        assertEquals(listOf(4, 3, 2), CardDensity.entries.map { it.columns(358f, 92f) })
    }

    @Test fun narrowAndWideWindowsKeepUsableColumnCounts() {
        CardDensity.entries.forEach {
            assertTrue(it.columns(10f, 108f) >= 2)
            assertTrue(it.columns(4000f, 108f) <= 8)
        }
    }
}
