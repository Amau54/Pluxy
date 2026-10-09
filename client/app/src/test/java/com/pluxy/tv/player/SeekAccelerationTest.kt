package com.pluxy.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekAccelerationTest {
    @Test
    fun `short press always moves exactly ten seconds`() {
        assertEquals(10_000L, SeekAcceleration.distanceMs(0L))
        assertEquals(10_000L, SeekAcceleration.distanceMs(399L))
        assertEquals("Précis", SeekAcceleration.speedLabel(399L))
    }

    @Test
    fun `hold accelerates continuously and monotonically`() {
        val samples = (0L..8_000L step 50L).map(SeekAcceleration::distanceMs)
        assertTrue(samples.zipWithNext().all { (before, after) -> after >= before })
        assertEquals("×1", SeekAcceleration.speedLabel(400L))
        assertEquals("×2", SeekAcceleration.speedLabel(1_000L))
        assertEquals("×4", SeekAcceleration.speedLabel(2_000L))
        assertEquals("×8", SeekAcceleration.speedLabel(4_000L))
        assertEquals("×16", SeekAcceleration.speedLabel(6_500L))
        assertEquals("×32", SeekAcceleration.speedLabel(10_000L))
        assertEquals("×64", SeekAcceleration.speedLabel(15_000L))
    }

    @Test fun `a long hold can cross a film without a discontinuous jump`() {
        assertEquals(316_000L, SeekAcceleration.distanceMs(6_500L))
        assertEquals(876_000L, SeekAcceleration.distanceMs(10_000L))
        assertEquals(2_476_000L, SeekAcceleration.distanceMs(15_000L))
        assertTrue(SeekAcceleration.distanceMs(15_001L) - SeekAcceleration.distanceMs(14_999L) < 1_000L)
    }

    @Test
    fun `acceleration is based on elapsed time not key repeat count`() {
        assertEquals(16_000L, SeekAcceleration.distanceMs(1_000L))
        assertEquals(36_000L, SeekAcceleration.distanceMs(2_000L))
        assertEquals(116_000L, SeekAcceleration.distanceMs(4_000L))
    }

    @Test fun `every faster stage starts without a position jump`() {
        for (boundary in listOf(1_000L, 2_000L, 4_000L, 6_500L, 10_000L, 15_000L)) {
            assertTrue(SeekAcceleration.distanceMs(boundary + 1) -
                SeekAcceleration.distanceMs(boundary - 1) in 0L..1_000L)
        }
    }
}
