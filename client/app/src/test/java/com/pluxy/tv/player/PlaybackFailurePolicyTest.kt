package com.pluxy.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.PlaybackException

class PlaybackFailurePolicyTest {
    @Test fun videoDecoderFailureIsNotANetworkOrAudioFailure() {
        assertTrue(PlaybackFailurePolicy.isVideoDecoderFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, "video/dolby-vision"))
        assertTrue(PlaybackFailurePolicy.isVideoDecoderFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, "video/hevc"))
        assertFalse(PlaybackFailurePolicy.isVideoDecoderFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, "video/hevc"))
        assertFalse(PlaybackFailurePolicy.isVideoDecoderFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, "audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.isVideoDecoderFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }

    @Test fun videoCompatibilityRequiresServerAndRespectsForcedPassthrough() {
        assertTrue(PlaybackFailurePolicy.canOfferVideoCompatibility(true, false))
        assertFalse(PlaybackFailurePolicy.canOfferVideoCompatibility(false, false))
        assertFalse(PlaybackFailurePolicy.canOfferVideoCompatibility(true, true))
    }

    @Test fun audioOrUnknownRendererCannotBlacklistVideo() {
        assertFalse(PlaybackFailurePolicy.isVideoRenderer("audio/true-hd"))
        assertFalse(PlaybackFailurePolicy.isVideoRenderer("audio/vnd.dts"))
        assertFalse(PlaybackFailurePolicy.isVideoRenderer(null))
        assertTrue(PlaybackFailurePolicy.isVideoRenderer("video/hevc"))
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
