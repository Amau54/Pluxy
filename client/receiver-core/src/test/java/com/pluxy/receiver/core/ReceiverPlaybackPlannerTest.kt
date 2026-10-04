package com.pluxy.receiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverPlaybackPlannerTest {
    private val h264 = RemoteMedia(
        id = "one",
        title = "Film",
        url = "http://192.168.1.2:8096/stream/direct/one",
        mimeType = "video/mp4",
        kind = "movie",
        container = "mp4",
        videoCodec = "h264",
        audioCodec = "aac",
        height = 1080,
    )

    @Test
    fun `keeps direct play when receiver profile matches`() {
        val plan = ReceiverPlaybackPlanner.plan(
            h264,
            ReceiverCapabilities(
                containers = setOf("mp4"),
                videoCodecs = setOf("h264"),
                audioCodecs = setOf("aac"),
                maxVideoHeight = 1080,
            ),
            serverCanAdapt = false,
        )
        assertEquals(PlaybackSuitability.DIRECT, plan.suitability)
    }

    @Test
    fun `requests adaptation instead of guessing for unsupported source`() {
        val plan = ReceiverPlaybackPlanner.plan(
            h264.copy(container = "mkv", videoCodec = "av1", height = 2160),
            ReceiverCapabilities(
                containers = setOf("mp4"),
                videoCodecs = setOf("h264"),
                maxVideoHeight = 1080,
            ),
            serverCanAdapt = true,
        )
        assertEquals(PlaybackSuitability.ADAPTED, plan.suitability)
        assertTrue(plan.reasons.size >= 2)
    }

    @Test
    fun `refuses unsupported direct stream when Android cannot adapt`() {
        val plan = ReceiverPlaybackPlanner.plan(
            h264.copy(audioCodec = "truehd"),
            ReceiverCapabilities(audioCodecs = setOf("aac")),
            serverCanAdapt = false,
        )
        assertEquals(PlaybackSuitability.UNSUPPORTED, plan.suitability)
    }
}
