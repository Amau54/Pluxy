package com.pluxy.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekAccelerationTest {
    @Test
    fun `short press always moves exactly five seconds`() {
        assertEquals(5_000L, SeekAcceleration.distanceMs(0L))
        assertEquals(5_000L, SeekAcceleration.distanceMs(399L))
        assertEquals("Précis", SeekAcceleration.speedLabel(399L))
    }

    @Test
    fun `hold accelerates continuously and monotonically`() {
        val samples = (0L..8_000L step 50L).map(SeekAcceleration::distanceMs)
        assertTrue(samples.zipWithNext().all { (before, after) -> after >= before })
        assertEquals("×1", SeekAcceleration.speedLabel(400L))
        assertEquals("×2", SeekAcceleration.speedLabel(800L))
        assertEquals("×4", SeekAcceleration.speedLabel(1_600L))
        assertEquals("×8", SeekAcceleration.speedLabel(3_200L))
        assertEquals("×16", SeekAcceleration.speedLabel(5_200L))
        assertEquals("×32", SeekAcceleration.speedLabel(8_000L))
        assertEquals("×64", SeekAcceleration.speedLabel(12_000L))
    }

    @Test fun `a long hold can cross a film without a discontinuous jump`() {
        assertEquals(249_000L, SeekAcceleration.distanceMs(5_200L))
        assertEquals(697_000L, SeekAcceleration.distanceMs(8_000L))
        assertEquals(1_977_000L, SeekAcceleration.distanceMs(12_000L))
        assertTrue(SeekAcceleration.distanceMs(12_001L) - SeekAcceleration.distanceMs(11_999L) < 1_000L)
    }

    @Test
    fun `acceleration is based on elapsed time not key repeat count`() {
        assertEquals(9_000L, SeekAcceleration.distanceMs(800L))
        assertEquals(25_000L, SeekAcceleration.distanceMs(1_600L))
        assertEquals(89_000L, SeekAcceleration.distanceMs(3_200L))
    }

    @Test fun `every faster stage starts without a position jump`() {
        for (boundary in listOf(800L, 1_600L, 3_200L, 5_200L, 8_000L, 12_000L)) {
            assertTrue(SeekAcceleration.distanceMs(boundary + 1) -
                SeekAcceleration.distanceMs(boundary - 1) in 0L..1_000L)
        }
    }
}
