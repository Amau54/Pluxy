package com.pluxy.tv.ui

import android.content.Intent
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.player.PlayerActivity
import com.pluxy.tv.player.PlaybackStrategy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL

class ConversionPlaybackTest {
    @Test fun realConversionKeepsThePositionAfterSeekingAndManualQualityStaysFixed() {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("conversion_test_url").orEmpty()
        val id = args.getString("conversion_test_item").orEmpty()
        assumeTrue("Requires an isolated media server", server.isNotBlank() && id.isNotBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val json = URL("$server/api/library/items/$id").openConnection().apply {
            connectTimeout = 10_000; readTimeout = 10_000
        }.getInputStream().bufferedReader().use { it.readText() }
        PluxyApplication.setServer(context, server, "Test conversion")
        PluxyApplication.setPlaybackStrategy(context, "compatibility")
        PluxyApplication.setConversionQuality(context, "auto")
        PluxyApplication.setAudioOutputMode(context, "compatible")
        val activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM, json).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        val type = PlayerActivity::class.java
        val playerField = type.getDeclaredField("player").apply { isAccessible = true }
        val position = type.getDeclaredMethod("absolutePositionMs").apply { isAccessible = true }
        fun awaitPosition(target: Long) {
            val deadline = System.currentTimeMillis() + 60_000
            var ready = false
            while (System.currentTimeMillis() < deadline && !ready) {
                instrumentation.runOnMainSync {
                    val player = playerField.get(activity) as? Player
                    val current = position.invoke(activity) as Long
                    ready = player?.playbackState == Player.STATE_READY && current in target..(target + 8_000)
                }
                if (!ready) Thread.sleep(250)
            }
            assertTrue("Playback must resume at $target without returning to zero", ready)
        }
        try {
            awaitPosition(0)
            val seek = type.getDeclaredMethod("goToAbsolute", Long::class.javaPrimitiveType).apply { isAccessible = true }
            for (target in listOf(600_000L, 900_000L, 910_000L)) {
                instrumentation.runOnMainSync { seek.invoke(activity, target) }
                awaitPosition(target)
            }
            val lower = type.getDeclaredMethod("maybeDownshift", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            instrumentation.runOnMainSync { assertEquals(true, lower.invoke(activity, false)) }
            awaitPosition(910_000L)
            PluxyApplication.setConversionQuality(context, "h264_720")
            val prepare = type.getDeclaredMethod("reprepareForStrategy", PlaybackStrategy::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync { prepare.invoke(activity, PlaybackStrategy.COMPATIBILITY) }
            awaitPosition(910_000L)
            instrumentation.runOnMainSync {
                assertEquals(false, lower.invoke(activity, false))
                (playerField.get(activity) as? Player)?.pause()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
