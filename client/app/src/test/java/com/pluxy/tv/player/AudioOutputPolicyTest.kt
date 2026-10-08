package com.pluxy.tv.player

import com.pluxy.tv.api.ClientCapabilities
import org.junit.Assert.*
import org.junit.Test

class AudioOutputPolicyTest {
    private fun detected() = ClientCapabilities(
        supportedAudioCodecs = listOf("aac", "ac3", "eac3"), maxAudioChannels = 8,
        supportsHevc = false, supportsHevc10bit = false, supportsHdr = false,
        supportsH264 = true, supportsHdr10 = false, supportedContainers = listOf("mkv", "mp4"),
        screenWidth = 1920, screenHeight = 1080,
        maxVideoHeight = 1080, maxBitrateMbps = 8,
    )

    @Test fun forcedPassthroughOverridesAnUnderreportedTvWithoutChangingVideo() {
        val source = detected().copy(maxAudioChannels = 2)
        val forced = AudioOutputPolicy.capabilities(source, AudioOutputMode.PASSTHROUGH)
        assertTrue(forced.supportedAudioCodecs.containsAll(listOf("truehd", "dtshd", "dts", "ac3", "eac3")))
        assertEquals(8, forced.maxAudioChannels)
        assertEquals(source.supportsHevc10bit, forced.supportsHevc10bit)
        assertEquals(source.maxVideoHeight, forced.maxVideoHeight)
        assertEquals(source.maxBitrateMbps, forced.maxBitrateMbps)
    }

    @Test fun automaticRecoveryPreservesSurroundBeforeUsingStereo() {
        val first = AudioOutputPolicy.capabilities(detected(), AudioOutputMode.AUTO, 1, "truehd")
        assertTrue(first.supportedAudioCodecs.containsAll(listOf("eac3", "ac3")))
        assertEquals(6, first.maxAudioChannels)
        assertFalse(first.supportedAudioCodecs.contains("truehd"))
        val second = AudioOutputPolicy.capabilities(detected(), AudioOutputMode.AUTO, 2, "eac3")
        assertEquals(listOf("aac"), second.supportedAudioCodecs)
        assertEquals(2, second.maxAudioChannels)
    }

    @Test fun aFailedDolbyFormatIsExcludedFromTheNextNegotiation() {
        val first = AudioOutputPolicy.capabilities(detected(), AudioOutputMode.AUTO, 1, "eac3")
        assertEquals(listOf("aac", "ac3"), first.supportedAudioCodecs)
    }

    @Test fun originalVideoAndAudioCompatibilityAreIndependentChoices() {
        val compatible = AudioOutputPolicy.capabilities(detected(), AudioOutputMode.COMPATIBLE)
        val original = AudioOutputPolicy.originalVideoCapabilities(compatible)
        assertTrue(original.supportsHevc10bit)
        assertTrue(original.supportsHdr)
        assertNull(original.maxBitrateMbps)
        assertEquals(Int.MAX_VALUE, original.maxVideoHeight)
        assertEquals(listOf("aac"), original.supportedAudioCodecs)
        assertEquals(2, original.maxAudioChannels)
    }

    @Test fun forcedPassthroughNeverSilentlyAdaptsAndRecoveryIsBounded() {
        assertFalse(AudioOutputPolicy.canAdapt(PlaybackStrategy.DIRECT, AudioOutputMode.PASSTHROUGH, true, 0, "audio/true-hd"))
        assertTrue(AudioOutputPolicy.canAdapt(PlaybackStrategy.DIRECT, AudioOutputMode.AUTO, true, 0, "audio/true-hd"))
        assertTrue(AudioOutputPolicy.canAdapt(PlaybackStrategy.DIRECT, AudioOutputMode.AUTO, true, 1, "audio/eac3"))
        assertFalse(AudioOutputPolicy.canAdapt(PlaybackStrategy.DIRECT, AudioOutputMode.AUTO, true, 2, "audio/mp4a-latm"))
        assertFalse(AudioOutputPolicy.canAdapt(PlaybackStrategy.AUTO, AudioOutputMode.AUTO, false, 0, "audio/true-hd"))
        assertFalse(AudioOutputPolicy.canAdapt(PlaybackStrategy.AUTO, AudioOutputMode.AUTO, true, 0, "video/hevc"))
    }

    @Test fun unknownSettingsRemainAutomatic() {
        assertEquals(AudioOutputMode.AUTO, AudioOutputMode.fromKey("unknown"))
    }
}
