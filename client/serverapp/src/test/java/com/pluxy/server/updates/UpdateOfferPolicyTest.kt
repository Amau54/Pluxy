package com.pluxy.server.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateOfferPolicyTest {
    @Test fun startupIgnoresAPreviousSessionDismissalButDoesNotRepeatWithinTheSession() {
        val policy = UpdateOfferPolicy()
        assertTrue(policy.mayOffer(100, 100_000))
        policy.markOffered()
        assertFalse(policy.mayOffer(101, 100_000))
        policy.beginSession()
        assertTrue(policy.mayOffer(102, 100_000))
        policy.markOffered()
        assertTrue(policy.mayOffer(100_001, 100_000))
    }
}
