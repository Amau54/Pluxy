package com.pluxy.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverProfilePolicyTest {
    private val media = RemoteMedia(
        id = "one",
        title = "Film",
        url = "http://192.168.1.2/one",
        mimeType = "video/x-matroska",
        kind = "movie",
        container = "mkv",
        videoCodec = "hevc",
        audioCodec = "truehd",
        height = 2160,
    )

    @Test
    fun `video failure narrows only the rejected video codec`() {
        val learned = ReceiverProfilePolicy.recordFailure(
            LearnedReceiverProfile(),
            media,
            UnsupportedFormatComponent.VIDEO,
        )
        val applied = ReceiverProfilePolicy.apply(
            ReceiverCapabilities(
                containers = setOf("mp4", "mkv"),
                videoCodecs = setOf("h264", "hevc"),
                audioCodecs = setOf("aac", "truehd"),
            ),
            learned,
        )
        assertTrue("mkv" in applied.containers)
        assertFalse("hevc" in applied.videoCodecs)
        assertTrue("h264" in applied.videoCodecs)
        assertTrue("truehd" in applied.audioCodecs)
    }

    @Test
    fun `successful 4k playback records stable ceiling`() {
        val learned = ReceiverProfilePolicy.recordSuccess(LearnedReceiverProfile(), media)
        val effective = ReceiverProfilePolicy.apply(
            ReceiverCapabilities(
                containers = setOf("mp4"),
                videoCodecs = setOf("h264"),
                maxVideoHeight = 1080,
            ),
            learned,
        )
        assertEquals(2160, learned.stableVideoHeight)
        assertEquals(1, learned.successCount)
        assertEquals(2160, effective.maxVideoHeight)
        assertTrue("mkv" in effective.containers)
        assertTrue("hevc" in effective.videoCodecs)
    }
}
