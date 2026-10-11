package com.pluxy.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyProfileSelectionTest {
    @Test fun `general remains default even with an old profile selection`() {
        assertEquals("default", FamilyProfileSelection.resolve(false, "a".repeat(32)))
        assertEquals("default", FamilyProfileSelection.resolve(false, null))
    }
    @Test fun `multiple profiles need explicit activation`() {
        assertEquals("a".repeat(32), FamilyProfileSelection.resolve(true, "a".repeat(32)))
        assertEquals("default", FamilyProfileSelection.resolve(true, "default"))
    }
    @Test fun `invalid saved profiles safely use general`() {
        assertEquals("default", FamilyProfileSelection.resolve(true, "../profile"))
        assertEquals("default", FamilyProfileSelection.resolve(true, null))
    }
}
