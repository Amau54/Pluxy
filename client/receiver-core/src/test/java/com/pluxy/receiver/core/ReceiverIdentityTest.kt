package com.pluxy.receiver.core

import org.junit.Assert.*
import org.junit.Test

class ReceiverIdentityTest {
    @Test fun `selected receiver mismatch prevents load`() {
        val identity = ReceiverIdentity("tv-one")
        assertThrows(IllegalStateException::class.java) { identity.load("tv-two") }
        assertFalse(identity.loaded)
    }
    @Test fun `old controller loses ownership on route switch or disconnect`() {
        val identity = ReceiverIdentity(null)
        identity.load("tv-one")
        assertTrue(identity.owns("tv-one"))
        assertFalse(identity.owns("tv-two"))
        assertFalse(identity.owns(null))
    }
}
