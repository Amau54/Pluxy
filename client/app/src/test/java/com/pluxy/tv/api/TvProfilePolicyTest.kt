package com.pluxy.tv.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvProfilePolicyTest {
    @Test fun `anciennes limites ambigues sont oubliees une seule fois`() {
        val old = LearnedTvProfile(unsupportedVideoCodecs = setOf("hevc"), maxStableBitrateMbps = 8, revision = 4)
        val migrated = TvProfilePolicy.migrate(old, 0)
        assertTrue(migrated.unsupportedVideoCodecs.isEmpty())
        assertNull(migrated.maxStableBitrateMbps)
        assertEquals(5, migrated.revision)
        assertEquals(migrated, TvProfilePolicy.migrate(migrated, TvProfilePolicy.SCHEMA_VERSION))
    }

    @Test fun `nouveaux echecs video confirmes restent memorises`() {
        val current = LearnedTvProfile(unsupportedVideoCodecs = setOf("hevc10"), revision = 1)
        assertEquals(current, TvProfilePolicy.migrate(current, TvProfilePolicy.SCHEMA_VERSION))
    }

    @Test
    fun `une coupure generique ne condamne pas un codec au premier incident`() {
        val first = TvProfilePolicy.recordFormatFailure(
            LearnedTvProfile(), "hevc", "mkv", deterministic = false, nowMs = 1,
        )
        assertTrue(first.unsupportedVideoCodecs.isEmpty())
        val second = TvProfilePolicy.recordFormatFailure(
            first, "hevc", "mkv", deterministic = false, nowMs = 2,
        )
        assertTrue("hevc" in second.unsupportedVideoCodecs)
        assertTrue("mkv" in second.unsupportedContainers)
    }

    @Test
    fun `le profil applique codecs conteneurs et plafond appris`() {
        val detected = capabilities()
        val learned = LearnedTvProfile(
            unsupportedVideoCodecs = setOf("hevc"),
            unsupportedContainers = setOf("mkv"),
            maxStableBitrateMbps = 28,
            revision = 4,
        )
        val effective = TvProfilePolicy.apply(detected, learned, "tv1", "Salon")

        assertFalse(effective.supportsHevc)
        assertFalse("hevc" in effective.supportedVideoCodecs)
        assertFalse("mkv" in effective.supportedContainers)
        assertEquals(28, effective.maxBitrateMbps)
        assertEquals("tv1", effective.profileId)
        assertEquals(4, effective.profileRevision)
    }

    @Test
    fun `le plafond reseau descend puis remonte seulement apres deux lectures stables`() {
        val limited = TvProfilePolicy.recordNetworkConstraint(
            LearnedTvProfile(), 60.0, nowMs = 1,
        )
        assertEquals(42, limited.maxStableBitrateMbps)
        val one = TvProfilePolicy.recordStablePlayback(limited, 50.0, nowMs = 2)
        assertEquals(42, one.maxStableBitrateMbps)
        val two = TvProfilePolicy.recordStablePlayback(one, 50.0, nowMs = 3)
        assertEquals(50, two.maxStableBitrateMbps)
    }

    @Test
    fun `sans incident aucun plafond artificiel nest ajoute`() {
        val effective = TvProfilePolicy.apply(capabilities(), LearnedTvProfile(), "tv", "TV")
        assertNull(effective.maxBitrateMbps)
    }

    @Test
    fun `un echec main10 conserve le hevc huit bits`() {
        val learned = LearnedTvProfile(unsupportedVideoCodecs = setOf("hevc10"))
        val effective = TvProfilePolicy.apply(capabilities(), learned, "tv", "TV")

        assertTrue(effective.supportsHevc)
        assertFalse(effective.supportsHevc10bit)
        assertTrue("hevc" in effective.supportedVideoCodecs)
    }

    private fun capabilities() = ClientCapabilities(
        supportsHevc = true,
        supportsHevc10bit = true,
        supportsH264 = true,
        supportsAv1 = true,
        supportsHdr10 = true,
        supportsHdr = true,
        supportedContainers = listOf("mp4", "mkv"),
        supportedAudioCodecs = listOf("aac"),
        supportedVideoCodecs = listOf("h264", "hevc", "av1"),
        maxAudioChannels = 6,
        maxBitrateMbps = null,
        maxVideoHeight = 2160,
        screenWidth = 3840,
        screenHeight = 2160,
    )
}
