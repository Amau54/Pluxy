package com.pluxy.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteRangeTest {
    @Test
    fun `missing range means full response`() {
        assertNull(ByteRange.parse(null, 1_000))
    }

    @Test
    fun `open range reaches end of file`() {
        assertEquals(ByteRange(250, 999), ByteRange.parse("bytes=250-", 1_000))
    }

    @Test
    fun `end is clamped to file size`() {
        assertEquals(ByteRange(100, 999), ByteRange.parse("bytes=100-9999", 1_000))
    }

    @Test
    fun `suffix range returns final bytes`() {
        assertEquals(ByteRange(900, 999), ByteRange.parse("bytes=-100", 1_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `range beyond file is rejected`() {
        ByteRange.parse("bytes=1000-", 1_000)
    }

    @Test
    fun `HEAD ignores range including an invalid range`() {
        assertNull(ByteRange.forRequest("HEAD", mapOf("range" to "bytes=1000-"), 1_000, "\"v1\""))
    }

    @Test
    fun `only a current strong If-Range validator allows partial delivery`() {
        val range = mapOf("range" to "bytes=250-", "if-range" to "\"v1\"")
        assertEquals(ByteRange(250, 999), ByteRange.forRequest("GET", range, 1_000, "\"v1\""))
        assertNull(ByteRange.forRequest("GET", range, 1_000, "\"v2\""))
        assertNull(ByteRange.forRequest("GET", range + ("if-range" to "W/\"v1\""), 1_000, "\"v1\""))
    }

    @Test
    fun `unknown range units are ignored`() {
        assertNull(ByteRange.forRequest("GET", mapOf("range" to "frames=1-2"), 1_000, "\"v1\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `multiple ranges are rejected`() {
        ByteRange.parse("bytes=0-10,20-30", 1_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty suffix is unsatisfiable`() {
        ByteRange.parse("bytes=-0", 1_000)
    }

    @Test
    fun `suffix larger than file covers whole file`() {
        assertEquals(ByteRange(0, 999), ByteRange.parse("bytes=-2000", 1_000))
    }
}
