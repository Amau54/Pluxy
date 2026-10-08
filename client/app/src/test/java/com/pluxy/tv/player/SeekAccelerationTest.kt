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
        assertEquals("×2", SeekAcceleration.speedLabel(1_200L))
        assertEquals("×4", SeekAcceleration.speedLabel(2_500L))
        assertEquals("×8", SeekAcceleration.speedLabel(5_000L))
        assertEquals("×16", SeekAcceleration.speedLabel(8_000L))
        assertEquals("×32", SeekAcceleration.speedLabel(12_000L))
        assertEquals("×64", SeekAcceleration.speedLabel(18_000L))
    }

    @Test fun `a long hold can cross a film without a discontinuous jump`() {
        assertEquals(384_000L, SeekAcceleration.distanceMs(8_000L))
        assertEquals(1_024_000L, SeekAcceleration.distanceMs(12_000L))
        assertEquals(2_944_000L, SeekAcceleration.distanceMs(18_000L))
        assertTrue(SeekAcceleration.distanceMs(18_001L) - SeekAcceleration.distanceMs(17_999L) < 1_000L)
    }

    @Test
    fun `acceleration is based on elapsed time not key repeat count`() {
        assertEquals(18_000L, SeekAcceleration.distanceMs(1_200L))
        assertEquals(44_000L, SeekAcceleration.distanceMs(2_500L))
        assertEquals(144_000L, SeekAcceleration.distanceMs(5_000L))
    }
}
