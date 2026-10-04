package com.pluxy.tv.player

import com.pluxy.tv.api.BufferConfig
import com.pluxy.tv.api.PlaybackDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPlannerTest {

    @Test
    fun `auto starts direct then offers two adaptive tiers`() {
        val plan = PlaybackPlanner.build(
            strategy = PlaybackStrategy.AUTO,
            itemId = "film",
            decision = decision("/stream/direct/film"),
            transcodingAvailable = true,
        )

        assertEquals(
            listOf(
                "/stream/direct/film",
                "/stream/hls/film/main/index.m3u8",
                "/stream/hls/film/compat/index.m3u8",
            ),
            plan.map { it.url },
        )
        assertEquals(1, PlaybackPlanner.nextAdaptiveIndex(plan, 0))
        assertEquals(2, PlaybackPlanner.nextAdaptiveIndex(plan, 1))
        assertEquals(1, PlaybackPlanner.recoveryTargetIndex(plan, 2, listOf(0, 1)))
        assertEquals(0, PlaybackPlanner.recoveryTargetIndex(plan, 1, listOf(0)))
    }

    @Test
    fun `direct mode ignores a server transcode decision`() {
        val plan = PlaybackPlanner.build(
            strategy = PlaybackStrategy.DIRECT,
            itemId = "film",
            decision = decision(
                url = "/stream/hls/film/compat/index.m3u8",
                mode = "transcode",
                delivery = "hls",
                compat = true,
            ),
            transcodingAvailable = true,
        )

        assertEquals(listOf("/stream/direct/film"), plan.map { it.url })
        assertEquals(-1, PlaybackPlanner.nextAdaptiveIndex(plan, 0))
    }

    @Test
    fun `auto skips hevc adaptive tier when television cannot decode hevc`() {
        val plan = PlaybackPlanner.build(
            strategy = PlaybackStrategy.AUTO,
            itemId = "film",
            decision = decision("/stream/direct/film"),
            transcodingAvailable = true,
            supportsHevc = false,
        )

        assertEquals(
            listOf(
                "/stream/direct/film",
                "/stream/hls/film/compat/index.m3u8",
            ),
            plan.map { it.url },
        )
    }

    @Test
    fun `compatibility uses h264 on pc and direct only as error fallback`() {
        val plan = PlaybackPlanner.build(
            strategy = PlaybackStrategy.COMPATIBILITY,
            itemId = "film",
            decision = decision("/stream/direct/film"),
            transcodingAvailable = true,
        )

        assertEquals("/stream/hls/film/compat/index.m3u8", plan[0].url)
        assertEquals(PlaybackTier.EMERGENCY, plan[1].tier)
        assertFalse(plan[1].adaptiveTarget)
        assertEquals(-1, PlaybackPlanner.nextAdaptiveIndex(plan, 0))
    }

    @Test
    fun `compatibility on android keeps original and reinforces buffer`() {
        val plan = PlaybackPlanner.build(
            strategy = PlaybackStrategy.COMPATIBILITY,
            itemId = "film",
            decision = decision("/stream/direct/film"),
            transcodingAvailable = false,
        )

        assertEquals(listOf("/stream/direct/film"), plan.map { it.url })
        assertTrue(
            PlaybackPlanner.shouldStartWithReinforcedBuffer(
                PlaybackStrategy.COMPATIBILITY,
                transcodingAvailable = false,
            ),
        )
    }

    @Test
    fun `adaptive search skips raw emergency fallback`() {
        val plan = PlaybackPlanner.build(
            strategy = PlaybackStrategy.AUTO,
            itemId = "film",
            decision = decision(
                url = "/stream/hls/film/main/index.m3u8",
                mode = "transcode",
                delivery = "hls",
            ),
            transcodingAvailable = true,
        )

        assertEquals(1, PlaybackPlanner.nextAdaptiveIndex(plan, 0))
        assertEquals(-1, PlaybackPlanner.nextAdaptiveIndex(plan, 1))
    }

    @Test
    fun `reinforced buffer remains within player bounds`() {
        val reinforced = PlaybackPlanner.reinforcedBuffer(
            BufferConfig(
                minBufferMs = 50_000,
                maxBufferMs = 240_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 10_000,
                targetBufferBytesMb = 64,
                backBufferMs = 30_000,
            ),
        )

        assertEquals(120_000, reinforced.minBufferMs)
        assertEquals(180_000, reinforced.maxBufferMs)
        assertEquals(30_000, reinforced.bufferForPlaybackAfterRebufferMs)
        assertEquals(256, reinforced.targetBufferBytesMb)
    }

    private fun decision(
        url: String,
        mode: String = "direct_play",
        delivery: String = "direct",
        compat: Boolean = false,
    ) = PlaybackDecision(
        mode = mode,
        compat = compat,
        delivery = delivery,
        streamUrl = url,
    )
}
