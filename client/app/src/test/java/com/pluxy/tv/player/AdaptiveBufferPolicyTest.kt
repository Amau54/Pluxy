package com.pluxy.tv.player

import com.pluxy.tv.api.BufferConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferPolicyTest {
    private fun policy(bitrate: Long = 10_000_000L, lowRam: Boolean = false): AdaptiveBufferPolicy {
        val runtime = PlaybackMemoryPolicy.resolve(
            BufferConfig(60_000, 120_000, 1_500, 5_000, 256, 30_000),
            256, true, lowRam, bitrate)
        return AdaptiveBufferPolicy(runtime, bitrate)
    }

    @Test fun `initial load and repeated seeks keep a quick start without counting a network failure`() {
        val policy = policy()
        repeat(20) { policy.observe(false, null) }
        assertEquals(0, policy.interruptions)
        assertEquals(1_500L, policy.requiredPlaybackMs(false, 1f))
    }

    @Test fun `one buffering episode cannot be counted at every loader poll`() {
        val policy = policy()
        repeat(500) { policy.observe(true, 10_000L) }
        assertEquals(1, policy.interruptions)
        assertEquals(7_500L, policy.requiredPlaybackMs(true, 1f))
    }

    @Test fun `repeated outages progressively increase reserve without rebuilding the player`() {
        val policy = policy(1_000_000L)
        val reserves = mutableListOf<Long>()
        repeat(10) {
            policy.observe(true, 10_000L + it * 20_000L)
            reserves.add(policy.requiredPlaybackMs(true, 1f))
            policy.observe(false, null)
        }
        assertEquals(listOf(7_500L, 11_250L, 16_875L, 25_312L), reserves.take(4))
        assertTrue(reserves.all { it <= 30_000L })
        assertEquals(4, policy.interruptions)
    }

    @Test fun `heavy HDR cannot request a reserve larger than the available memory`() {
        val policy = policy(120_000_000L)
        policy.observe(true, 1L)
        val capacityMs = (85 * 1024 * 1024 * 8.0 * .75 / 120_000_000 * 1000).toLong()
        assertEquals(capacityMs, policy.requiredPlaybackMs(true, 1f))
        assertEquals(capacityMs / 2, policy.requiredPlaybackMs(true, 2f))
    }

    @Test fun `small TV memory remains usable instead of waiting for fifteen impossible seconds`() {
        val policy = policy(200_000_000L, true)
        repeat(4) { policy.observe(true, it.toLong()) }
        assertTrue(policy.requiredPlaybackMs(true, 1f) in 250L..1100L)
    }

    @Test fun `missing metadata still bounds resume latency and ignores seek episodes`() {
        val policy = policy(0)
        policy.observe(true, null)
        policy.observe(true, null)
        assertEquals(1, policy.interruptions)
        policy.observe(false, null)
        assertEquals(1_500L, policy.requiredPlaybackMs(false, 1f))
        policy.observe(true, null)
        assertEquals(2, policy.interruptions)
    }

    @Test fun `changing to a lower bitrate stream restores a useful reserve`() {
        val policy = policy(120_000_000L)
        policy.observe(true, 1L)
        assertTrue(policy.requiredPlaybackMs(true, 1f) < 7_500L)
        policy.updateStreamBitrate(10_000_000L)
        assertEquals(7_500L, policy.requiredPlaybackMs(true, 1f))
        policy.updateStreamBitrate(-1L)
        assertEquals(7_500L, policy.requiredPlaybackMs(true, 1f))
    }
}
