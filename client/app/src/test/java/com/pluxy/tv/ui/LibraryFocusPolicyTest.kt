package com.pluxy.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFocusPolicyTest {
    @Test fun `saved target wins over initial load fallback`() {
        assertEquals(LibraryFocusIntent.RESTORE, libraryFocusIntent(true, true, true, false, false))
    }
    @Test fun `navigation to menu prevents a delayed request from taking focus`() {
        assertEquals(LibraryFocusIntent.KEEP, libraryFocusIntent(true, true, true, true, false))
    }
    @Test fun `initial load without target focuses first card`() {
        assertEquals(LibraryFocusIntent.FIRST, libraryFocusIntent(true, true, false, false, false))
    }
    @Test fun `refresh with menu focus keeps it`() {
        assertEquals(LibraryFocusIntent.KEEP, libraryFocusIntent(false, false, true, false, false))
    }
    @Test fun `navigation within grid can restore its latest remembered target`() {
        assertEquals(LibraryFocusIntent.RESTORE, libraryFocusIntent(true, false, true, true, true))
    }
    @Test fun `explicit search can focus empty recovery action from the menu`() {
        assertTrue(shouldFocusLibraryEmptyAction(false, true, false, false, true))
    }
    @Test fun `restoration without a remaining card can focus empty recovery action`() {
        assertTrue(shouldFocusLibraryEmptyAction(true, false, false, false, true))
    }
    @Test fun `navigation to menu prevents delayed empty result taking focus`() {
        assertFalse(shouldFocusLibraryEmptyAction(true, true, true, false, true))
    }
    @Test fun `silent empty refresh keeps menu focus`() {
        assertFalse(shouldFocusLibraryEmptyAction(false, false, false, false, true))
    }
    @Test fun `empty result transfers focus from grid to recovery action`() {
        assertTrue(shouldFocusLibraryEmptyAction(false, false, true, true, true))
    }
    @Test fun `no current focus alone cannot override intervening navigation`() {
        assertTrue(shouldFocusLibraryEmptyAction(false, false, false, false, false))
        assertFalse(shouldFocusLibraryEmptyAction(false, false, true, false, false))
    }
}
