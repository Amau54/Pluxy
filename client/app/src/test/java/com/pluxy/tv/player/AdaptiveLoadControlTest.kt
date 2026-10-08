package com.pluxy.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.*
import org.junit.Test

@OptIn(UnstableApi::class)
class AdaptiveLoadControlTest {
    private class EngineControl : LoadControl {
        val calls = mutableListOf<String>()
        val memory = DefaultAllocator(true, 64 * 1024)
        override fun onPrepared(playerId: PlayerId) { calls.add("prepared") }
        override fun onStopped(playerId: PlayerId) { calls.add("stopped") }
        override fun onReleased(playerId: PlayerId) { calls.add("released") }
        override fun getAllocator(playerId: PlayerId) = memory
        override fun getBackBufferDurationUs(playerId: PlayerId) = 500_000L
        override fun retainBackBufferFromKeyframe(playerId: PlayerId) = false
        override fun shouldContinueLoading(parameters: LoadControl.Parameters) = memory.totalBytesAllocated == 0
        override fun shouldStartPlayback(parameters: LoadControl.Parameters) = memory.totalBytesAllocated > 0
        override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline,
                                             mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long) = false
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
                                      trackSelections: Array<out ExoTrackSelection?>) { calls.add("tracks") }
    }
    private val budget = PlaybackMemoryPolicy.RuntimeBuffer(30_000, 60_000, 1_500, 5_000,
        64 * 1024, 500, false)
    private fun parameters(bufferedUs: Long) = LoadControl.Parameters(PlayerId.UNSET, Timeline.EMPTY,
        MediaPeriodId("test"), 0L, bufferedUs, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)

    @Test fun `all current Media3 lifecycle methods reach the engine instead of Java default stubs`() {
        val engine = EngineControl()
        val control = AdaptiveLoadControl(engine, budget, 0)
        control.onPrepared(PlayerId.UNSET)
        assertEquals(500_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
        assertFalse(control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        assertSame(engine.memory, control.getAllocator(PlayerId.UNSET))
        control.onTracksSelected(parameters(0), TrackGroupArray.EMPTY, emptyArray())
        assertFalse(control.shouldContinuePreloading(PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId("test"), 0))
        control.onStopped(PlayerId.UNSET)
        control.onReleased(PlayerId.UNSET)
        assertEquals(listOf("prepared", "tracks", "stopped", "released"), engine.calls)
    }

    @Test fun `an empty reserve cannot start but a filled reserve can`() {
        val control = AdaptiveLoadControl(EngineControl(), budget, 0)
        assertFalse(control.shouldStartPlayback(parameters(0)))
        assertFalse(control.shouldStartPlayback(parameters(1_000_000)))
        assertTrue(control.shouldStartPlayback(parameters(1_500_000)))
    }

    @Test fun `a bitrate spike that fills the memory budget cannot deadlock the player`() {
        val engine = EngineControl()
        val control = AdaptiveLoadControl(engine, budget, 0)
        control.onPrepared(PlayerId.UNSET)
        engine.memory.allocate()
        assertFalse(control.shouldContinueLoading(parameters(500_000)))
        assertTrue(control.shouldStartPlayback(parameters(500_000)))
        assertFalse(control.shouldStartPlayback(parameters(0)))
    }
}
