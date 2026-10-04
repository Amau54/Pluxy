package com.pluxy.receiver.core

import org.junit.Assert.*
import org.junit.Test

class ReceiverRejectionTest {
    private val video = RemoteMedia("a", "Film", "http://192.168.1.2/a", "video/mp4", "movie", "mp4", videoCodec = "h264", audioCodec = "aac")
    @Test fun `rejection of last advertised codec stays an explicit blocker`() {
        val profile = ReceiverProfilePolicy.recordFailure(LearnedReceiverProfile(), video, UnsupportedFormatComponent.VIDEO)
        val caps = ReceiverProfilePolicy.apply(ReceiverCapabilities(videoCodecs = setOf("h264")), profile)
        assertTrue(caps.videoCodecs.isEmpty())
        assertEquals(PlaybackSuitability.UNSUPPORTED, ReceiverPlaybackPlanner.plan(video, caps, false).suitability)
        assertEquals(PlaybackSuitability.ADAPTED, ReceiverPlaybackPlanner.plan(video, caps, true).suitability)
    }
    @Test fun `audio and image rejection also survive empty capability sets`() {
        val audio = video.copy(kind = "audio", videoCodec = null)
        val audioCaps = ReceiverProfilePolicy.apply(ReceiverCapabilities(audioCodecs = setOf("aac")),
            ReceiverProfilePolicy.recordFailure(LearnedReceiverProfile(), audio, UnsupportedFormatComponent.AUDIO))
        assertEquals(PlaybackSuitability.UNSUPPORTED, ReceiverPlaybackPlanner.plan(audio, audioCaps, false).suitability)
        val photo = video.copy(kind = "photo", mimeType = "image/jpeg", container = "jpg", videoCodec = null, audioCodec = null)
        val photoCaps = ReceiverProfilePolicy.apply(ReceiverCapabilities(imageMimeTypes = setOf("image/jpeg")),
            ReceiverProfilePolicy.recordFailure(LearnedReceiverProfile(), photo, UnsupportedFormatComponent.IMAGE))
        assertEquals(PlaybackSuitability.UNSUPPORTED, ReceiverPlaybackPlanner.plan(photo, photoCaps, false).suitability)
    }
    @Test fun `real success clears prior rejection with codec aliases normalized`() {
        val hevc = video.copy(videoCodec = "h265")
        val failed = ReceiverProfilePolicy.recordFailure(LearnedReceiverProfile(), hevc, UnsupportedFormatComponent.VIDEO)
        val succeeded = ReceiverProfilePolicy.recordSuccess(failed, hevc.copy(videoCodec = "hevc"))
        val caps = ReceiverProfilePolicy.apply(ReceiverCapabilities(), succeeded)
        assertEquals(PlaybackSuitability.DIRECT, ReceiverPlaybackPlanner.plan(hevc, caps, false).suitability)
        assertTrue(caps.rejectedVideoCodecs.isEmpty())
    }
}
