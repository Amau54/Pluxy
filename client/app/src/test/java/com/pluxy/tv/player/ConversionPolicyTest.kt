package com.pluxy.tv.player

import com.pluxy.tv.api.ConversionProfile
import com.pluxy.tv.api.PlaybackDecision
import com.pluxy.tv.api.ClientCapabilities
import com.pluxy.tv.api.MediaItem
import org.junit.Assert.*
import org.junit.Test

class ConversionPolicyTest {
    private val profiles = listOf(
        ConversionProfile("hevc_4k", "4K · H.265", "hevc", 3840, 2160, 50.0),
        ConversionProfile("hevc_1080", "1080p · H.265", "hevc", 1920, 1080, 12.0),
        ConversionProfile("h264_720", "720p · H.264", "h264", 1280, 720, 5.0))
    private val original = PlaybackDecision(mode="direct_play", streamUrl="/stream/direct/film")

    @Test fun conversionStartsHighAndCanDownshiftWithoutLosingPassthrough() {
        val plan=PlaybackPlanner.build(PlaybackStrategy.COMPATIBILITY,"film",original,true,
            preserveAudio=true,conversionProfiles=profiles)
        assertEquals(3,plan.size)
        assertTrue(plan.first().url.contains("/hevc_4k?copy_audio=true"))
        assertEquals(1,PlaybackPlanner.nextAdaptiveIndex(plan,0))
        assertTrue(plan.all { PlaybackSeekPolicy.isRemux(it.url) })
        assertTrue(ConversionPolicy.adaptive(PlaybackStrategy.COMPATIBILITY,"auto"))
    }
    @Test fun manualQualityIsFixedAndOriginalNeverConverts() {
        val fixed=PlaybackPlanner.build(PlaybackStrategy.COMPATIBILITY,"film",original,true,
            conversionProfiles=profiles,conversionQuality="h264_720")
        assertEquals(1,fixed.size)
        assertTrue(fixed.first().url.endsWith("/h264_720"))
        assertEquals(-1,PlaybackPlanner.nextAdaptiveIndex(fixed,0))
        assertFalse(ConversionPolicy.adaptive(PlaybackStrategy.COMPATIBILITY,"h264_720"))
        val direct=PlaybackPlanner.build(PlaybackStrategy.DIRECT,"film",original,true,conversionProfiles=profiles)
        assertEquals(listOf("/stream/direct/film"),direct.map { it.url })
    }
    @Test fun h264OnlyReaderSkipsHevcAndAutoRetainsOriginalFirst() {
        val plan=PlaybackPlanner.build(PlaybackStrategy.AUTO,"film",original,true,
            supportsHevc=false,conversionProfiles=profiles)
        assertEquals(listOf("/stream/direct/film","/stream/convert/film/h264_720"),plan.map { it.url })
        assertEquals(1,PlaybackPlanner.nextAdaptiveIndex(plan,0))
    }
    @Test fun conversionCannotBufferForeverWhileProducingTooSlowly() {
        assertFalse(PlaybackBufferWatchPolicy.isStuck(10_000, 1_000, true, conversion=true))
        assertTrue(PlaybackBufferWatchPolicy.isStuck(45_000, 1_000, true, conversion=true))
        assertTrue(PlaybackBufferWatchPolicy.isStuck(20_000, 15_000, true, conversion=true))
        assertFalse(PlaybackBufferWatchPolicy.isStuck(45_000, 1_000, true))
    }
    @Test fun sdrAndFullHdReadersNeverReceiveAnUnsupportedHdrOr4kProfile() {
        val caps=ClientCapabilities(true,true,true,supportsHdr10=true,supportsHdr=true,
            supportedContainers=listOf("mkv"),supportedAudioCodecs=listOf("aac"),maxAudioChannels=2,
            maxBitrateMbps=null,maxVideoHeight=2160,screenWidth=3840,screenHeight=2160)
        val item=MediaItem("film","Film","film.mkv","mkv",0,100.0,width=3840,height=1608,isHdr=true)
        assertEquals(listOf("h264_720"),ConversionPolicy.compatible(profiles,item,caps.copy(supportsHdr=false)).map { it.id })
        assertEquals(listOf("hevc_1080","h264_720"),ConversionPolicy.compatible(profiles,item,caps.copy(maxVideoHeight=1080)).map { it.id })
        assertEquals(listOf("hevc_1080","h264_720"),ConversionPolicy.compatible(profiles,item.copy(width=1920,height=1080),caps).map { it.id })
    }
    @Test fun forcedPassthroughNeverUsesAnAudioConvertedRemux() {
        val plan=PlaybackPlanner.build(PlaybackStrategy.AUTO,"film",original.copy(mode="direct_stream",
            streamUrl="/stream/remux/film",audioAction="transcode"),true,preserveAudio=true,conversionProfiles=profiles)
        assertEquals("/stream/direct/film",plan.first().url)
        assertFalse(plan.any { it.url.contains("/stream/remux/") })
        assertTrue(plan.drop(1).all { it.url.endsWith("?copy_audio=true") })
    }
}
