package com.pluxy.tv.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanUrlPolicyTest {
    @Test
    fun classifiesLanAddresses() {
        assertTrue(LanUrlPolicy.isTrustedAddress(InetAddress.getByName("192.168.1.20")))
        assertTrue(LanUrlPolicy.isTrustedAddress(InetAddress.getByName("fd00::20")))
        assertFalse(LanUrlPolicy.isTrustedAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun keepsStreamsOnSelectedServer() {
        assertEquals(
            "http://192.168.1.20:8420/stream/direct/abc",
            LanUrlPolicy.resolveSameServer("http://192.168.1.20:8420", "/stream/direct/abc"),
        )
        runCatching {
            LanUrlPolicy.resolveSameServer("http://192.168.1.20:8420", "https://example.com/movie")
        }.onSuccess { throw AssertionError("Une origine externe a ete acceptee") }
    }

    @Test
    fun restrictsMetadataDestinations() {
        assertTrue(LanUrlPolicy.safeTmdbImage("https://image.tmdb.org/t/p/w500/a.jpg") != null)
        assertTrue(LanUrlPolicy.safeYoutubeTrailer("https://www.youtube.com/watch?v=abc") != null)
        assertFalse(LanUrlPolicy.safeYoutubeTrailer("intent://example") != null)
    }
}
