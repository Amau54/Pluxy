package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test

class AutoSkipPolicyTest {
    @Test fun onlyCreditsEndingTheMediaCanAdvanceToTheNextEpisode() {
        assertTrue(SkipSegments.isFinalCredits(SkipSegment("credits", 900_000, 1_000_000), 1_000_000))
        assertFalse(SkipSegments.isFinalCredits(SkipSegment("credits", 900_000, 950_000), 1_000_000))
        assertFalse(SkipSegments.isFinalCredits(SkipSegment("intro", 0, 1_000_000), 1_000_000))
        assertFalse(SkipSegments.isFinalCredits(null, 1_000_000))
        assertFalse(SkipSegments.isFinalCredits(SkipSegment("credits", 900_000, 1_100_000), 1_000_000))
    }

    private val intro=SkipSegment("intro",10_000,40_000)
    private val credits=SkipSegment("credits",900_000,950_000)
    @Test fun disabledPausedAndUnknownSegmentsNeverJump() {
        val policy=AutoSkipPolicy()
        assertNull(policy.target("one",intro,false,true))
        assertNull(policy.target("one",intro,true,false))
        assertNull(policy.target("one",null,true,true))
        assertEquals(40_000L,policy.target("one",intro,true,true))
    }
    @Test fun creditsKeepThePostCreditSceneAndRewindsCannotLoop() {
        val policy=AutoSkipPolicy()
        assertEquals(40_000L,policy.target("one",intro,true,true))
        assertNull(policy.target("one",intro,true,true))
        assertNull(policy.target("one",intro.copy(endMs=41_000),true,true))
        assertEquals(950_000L,policy.target("one",credits,true,true))
        assertNull(policy.target("one",credits,true,true))
        assertEquals(40_000L,policy.target("two",intro,true,true))
    }
    @Test fun manualSkipIsRememberedAndLateAnalysisIsStillPolled() {
        val policy=AutoSkipPolicy()
        policy.mark("one","intro")
        assertNull(policy.target("one",intro,true,true))
        assertTrue((0 until AutoSkipPolicy.PROBE_ATTEMPTS).sumOf { AutoSkipPolicy.probeDelayMs(it) } > 1_200_000L)
        assertEquals(30_000L,AutoSkipPolicy.probeDelayMs(79))
    }
}
