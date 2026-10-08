package com.pluxy.tv.player

import com.pluxy.tv.api.BufferConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMemoryPolicyTest {

    @Test
    fun `low ram device caps time bytes and back buffer`() {
        val policy = PlaybackMemoryPolicy.resolve(
            requested = requestedBuffer(),
            memoryClassMb = 128,
            isTelevision = true,
            isLowRamDevice = true,
        )

        assertEquals(45_000, policy.minBufferMs)
        assertEquals(45_000, policy.maxBufferMs)
        assertEquals(16 * 1024 * 1024, policy.targetBufferBytes)
        assertEquals(0, policy.backBufferMs)
        assertFalse(policy.retainBackBufferFromKeyframe)
    }

    @Test
    fun `regular television uses standard heap without forcing six hundred megabytes`() {
        val policy = PlaybackMemoryPolicy.resolve(
            requested = requestedBuffer(),
            memoryClassMb = 256,
            isTelevision = true,
            isLowRamDevice = false,
        )

        assertEquals(60_000, policy.minBufferMs)
        assertEquals(120_000, policy.maxBufferMs)
        assertEquals(85 * 1024 * 1024, policy.targetBufferBytes)
        assertEquals(2_000, policy.backBufferMs)
        assertFalse(policy.retainBackBufferFromKeyframe)
    }

    @Test
    fun `mobile budget remains bounded on a large heap class`() {
        val policy = PlaybackMemoryPolicy.resolve(
            requested = requestedBuffer(),
            memoryClassMb = 512,
            isTelevision = false,
            isLowRamDevice = false,
        )

        assertEquals(90_000, policy.maxBufferMs)
        assertEquals(128 * 1024 * 1024, policy.targetBufferBytes)
    }

    @Test
    fun `misreported television still follows its small standard heap`() {
        val policy = PlaybackMemoryPolicy.resolve(
            requested = requestedBuffer(),
            memoryClassMb = 64,
            isTelevision = true,
            isLowRamDevice = false,
        )

        assertEquals(21 * 1024 * 1024, policy.targetBufferBytes)
    }

    @Test
    fun `heavy video leaves at least ninety percent of its byte budget ahead`() {
        val bitrate = 120_000_000L
        val policy = PlaybackMemoryPolicy.resolve(requestedBuffer(), 256, true, false, bitrate)
        val retainedBytes = policy.backBufferMs / 1000.0 * bitrate / 8 * 1.5
        assertTrue(retainedBytes <= policy.targetBufferBytes * 0.1)
        assertTrue(policy.backBufferMs < 500)
        assertFalse(policy.retainBackBufferFromKeyframe)
    }

    private fun requestedBuffer() = BufferConfig(
        minBufferMs = 60_000,
        maxBufferMs = 120_000,
        bufferForPlaybackMs = 5_000,
        bufferForPlaybackAfterRebufferMs = 15_000,
        targetBufferBytesMb = 600,
        backBufferMs = 60_000,
    )
}
