package com.pluxy.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyProgressTest {
    @Test fun `same media keeps independent pending resume per profile`() {
        val state = ProgressPendingState()
        val shared = PendingProgress("http://server:8420", "film", "session00", 1, 12000, 120000, 1)
        val family = shared.copy(profileId = "a".repeat(32), positionMs = 42000)
        state.add(shared); state.add(family)
        assertEquals(2, state.snapshot().size)
        assertEquals(setOf(12000L, 42000L), state.snapshot().map { it.positionMs }.toSet())
        state.acknowledge(shared)
        assertEquals(listOf(family), state.snapshot())
    }
}
