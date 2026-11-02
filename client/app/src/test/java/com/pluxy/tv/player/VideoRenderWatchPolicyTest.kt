package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test

class VideoRenderWatchPolicyTest {
    @Test fun audioClockAdvancingWithoutVideoOutputTriggersOneRecovery() {
        val policy = VideoRenderWatchPolicy()
        for (time in 0L..7_000L step 1_000) assertFalse(policy.observe("one", time, time, 40, 1, true))
        assertTrue(policy.observe("one", 8_000, 8_000, 40, 1, true))
        for (time in 9_000L..127_000L step 1_000) assertFalse(policy.observe("one", time, time, 40, 1, true))
        assertTrue(policy.observe("one", 128_000, 128_000, 40, 1, true))
        for (time in 129_000L..260_000L step 1_000) assertFalse(policy.observe("one", time, time, 40, 1, true))
        assertFalse(policy.observe("two", 261_000, 1_000, 40, 1, true))
        for (time in 262_000L..268_000L step 1_000) assertFalse(policy.observe("two", time, time - 260_000, 40, 1, true))
        assertTrue(policy.observe("two", 269_000, 9_000, 40, 1, true))
    }

    @Test fun ordinaryRenderingAndBriefFreezesNeverRestartVideo() {
        val policy = VideoRenderWatchPolicy()
        for (time in 0L..30_000L step 1_000) assertFalse(policy.observe("one", time, time, (time / 1_000).toInt() + 1, 1, true))
        for (time in 31_000L..37_000L step 1_000) assertFalse(policy.observe("one", time, time, 31, 1, true))
        assertFalse(policy.observe("one", 38_000, 38_000, 32, 1, true))
    }

    @Test fun pausesBufferingBackgroundAndNoFirstFrameCannotTriggerRecovery() {
        for (mode in 0..2) {
            val policy = VideoRenderWatchPolicy()
            for (time in 0L..30_000L step 1_000)
                assertFalse(policy.observe("one", time, if (mode == 0) 0 else time,
                    if (mode == 2) 0 else 40, 1, mode != 1))
        }
    }

    @Test fun seeksDecoderReplacementAndDelayedUiTicksRestartObservation() {
        val policy = VideoRenderWatchPolicy()
        for (time in 0L..7_000L step 1_000) assertFalse(policy.observe("one", time, time, 40, 1, true))
        assertFalse(policy.observe("one", 8_000, 80_000, 40, 1, true))
        assertFalse(policy.observe("one", 9_000, 3_000, 40, 1, true))
        assertFalse(policy.observe("one", 10_000, 4_000, 40, 2, true))
        assertFalse(policy.observe("one", 15_000, 9_000, 40, 2, true))
        assertFalse(policy.observe("one", 16_000, 10_000, 1, 1, true))
    }
}
