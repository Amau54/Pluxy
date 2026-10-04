package com.pluxy.server

import org.junit.Assert.*
import org.junit.Test

class SafGrantPolicyTest {
    @Test fun `shared or hidden folder keeps grant until its last reference is removed`() {
        val uri = "content://docs/tree/one"
        assertFalse(SafGrantPolicy.canRelease(uri, listOf(uri, "content://docs/tree/two")))
        assertTrue(SafGrantPolicy.canRelease(uri, listOf("content://docs/tree/two")))
        assertTrue(SafGrantPolicy.canRelease(uri, emptyList()))
    }
}
