package com.pluxy.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailurePolicyTest {
    @Test fun audioOrUnknownRendererCannotBlacklistVideo() {
        assertFalse(PlaybackFailurePolicy.isVideoRenderer("audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.isVideoRenderer("audio/vnd.dts"))
        assertFalse(PlaybackFailurePolicy.isVideoRenderer(null))
        assertTrue(PlaybackFailurePolicy.isVideoRenderer("video/hevc"))
    }

    @Test fun audioAdaptationIsBoundedAndRespectsStrictDirectPlay() {
        assertTrue(PlaybackFailurePolicy.canAdaptAudio(PlaybackStrategy.AUTO, true, false, "audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.canAdaptAudio(PlaybackStrategy.AUTO, true, true, "audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.canAdaptAudio(PlaybackStrategy.DIRECT, true, false, "audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.canAdaptAudio(PlaybackStrategy.AUTO, false, false, "audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.canAdaptAudio(PlaybackStrategy.AUTO, true, false, "video/hevc"))
        assertFalse(PlaybackFailurePolicy.canAdaptAudio(PlaybackStrategy.AUTO, true, false, null))
    }

    @Test fun missingSubtitleOrSegmentIsNotAMissingOriginal() {
        for (status in listOf(404, 410)) {
            assertTrue(PlaybackFailurePolicy.requiresUserActionForPath("/stream/direct/film", status))
            assertFalse(PlaybackFailurePolicy.requiresUserActionForPath("/stream/subs/film/0", status))
            assertFalse(PlaybackFailurePolicy.requiresUserActionForPath("/stream/hls/film/segment.ts", status))
        }
    }

    @Test fun missingOriginalStopsAutomaticRecovery() {
        assertTrue(PlaybackFailurePolicy.requiresUserAction("direct", 404))
        assertTrue(PlaybackFailurePolicy.requiresUserAction("direct", 410))
    }

    @Test fun expiredHlsSegmentsKeepAutomaticRecovery() {
        assertFalse(PlaybackFailurePolicy.requiresUserAction("hls", 404))
        assertFalse(PlaybackFailurePolicy.requiresUserAction("hls", 410))
    }

    @Test fun accessDeniedNeedsUserActionForEveryDelivery() {
        for (delivery in listOf("direct", "hls", null)) {
            assertTrue(PlaybackFailurePolicy.requiresUserAction(delivery, 401))
            assertTrue(PlaybackFailurePolicy.requiresUserAction(delivery, 403))
        }
    }

    @Test fun transientHttpFailuresKeepRecovery() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertFalse(PlaybackFailurePolicy.requiresUserAction("direct", status))
            assertFalse(PlaybackFailurePolicy.requiresUserAction("hls", status))
        }
    }

    @Test fun decoderAndUnknownFailuresKeepCompatibilityFallbacks() {
        assertFalse(PlaybackFailurePolicy.requiresUserAction("direct", null))
        assertFalse(PlaybackFailurePolicy.requiresUserAction(null, 404))
    }
}
