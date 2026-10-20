package com.pluxy.tv.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.player.MediaChapter
import com.pluxy.tv.player.PlayerActivity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL

class PlaybackEndErgonomicsTest {
    private var nextItem: MediaItem? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(activity: PlayerActivity, name: String): Any? =
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(activity: PlayerActivity, name: String) =
        PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail(message)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
    private fun withPlayer(test: (PlayerActivity) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("auto_skip_test_url").orEmpty()
        val id = args.getString("auto_skip_test_item").orEmpty()
        assumeTrue("Requires isolated chapter fixture", server.isNotBlank() && id.isNotBlank())
        val json = URL("$server/api/library/items/$id").openConnection().apply {
            connectTimeout = 10_000; readTimeout = 10_000
        }.getInputStream().bufferedReader().use { it.readText() }
        args.getString("auto_skip_next_item")?.let { next ->
            val data = URL("$server/api/library/items/$next").openConnection().apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }.getInputStream().bufferedReader().use { it.readText() }
            nextItem = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MediaItem::class.java).fromJson(data)
        }
        val before = PluxyApplication.autoSkipSegments(context)
        val autoplay = PluxyApplication.autoPlayNextEpisode(context)
        PluxyApplication.setServer(context, server, "Test ergonomie")
        PluxyApplication.setPlaybackStrategy(context, "direct")
        PluxyApplication.setAutoSkipSegments(context, false)
        PluxyApplication.setAutoPlayNextEpisode(context, true)
        val activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM, json).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        try {
            await("Player must be ready") { (field(activity, "player") as? Player)?.isPlaying == true }
            instrumentation.runOnMainSync {
                PlayerActivity::class.java.getDeclaredField("resumeHandled").apply { isAccessible = true }.set(activity, true)
                (field(activity, "resumeDialog") as? Dialog)?.dismiss()
                (field(activity, "player") as Player).pause()
            }
            test(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            PluxyApplication.setAutoSkipSegments(context, before)
            PluxyApplication.setAutoPlayNextEpisode(context, autoplay)
        }
    }
    @Test fun shortIntroIsHiddenAndFocusedIntroAndCreditsSkipWithOneOk() = withPlayer { activity ->
        val seek = PlayerActivity::class.java.getDeclaredMethod("goToAbsolute", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        instrumentation.runOnMainSync { seek.invoke(activity, 3_500L) }
        await("Seek must finish") { (field(activity, "player") as Player).let {
            it.playbackState == Player.STATE_READY && it.currentPosition >= 3_400
        } }
        instrumentation.runOnMainSync {
            call(activity, "updateSkipSegment")
            assertEquals(View.GONE, activity.findViewById<View>(R.id.skipSegment).visibility)
            PlayerActivity::class.java.getDeclaredField("skipChapters").apply { isAccessible = true }
                .set(activity, listOf(MediaChapter(0, 10_000, "Opening")))
            call(activity, "hideControls")
            call(activity, "updateSkipSegment")
            val button = activity.findViewById<Button>(R.id.skipSegment)
            assertEquals(View.VISIBLE, button.visibility)
            assertNull(button.background)
            assertTrue("Skip must receive TV focus automatically", button.hasFocus())
            assertTrue("Focused text must have a discreet underline", button.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG != 0)
            assertEquals("Passer l’intro", button.text.toString())
        }
        capture("tv-skip-minimal.png")
        instrumentation.runOnMainSync {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        await("One OK must seek straight to the intro end") { (field(activity, "player") as Player).currentPosition >= 9_900 }
        instrumentation.runOnMainSync { assertFalse((field(activity, "player") as Player).playWhenReady) }
        instrumentation.runOnMainSync { seek.invoke(activity, 19_000L) }
        await("Credits seek must finish") { (field(activity, "player") as Player).let {
            it.playbackState == Player.STATE_READY && it.currentPosition >= 18_900
        } }
        instrumentation.runOnMainSync {
            PlayerActivity::class.java.getDeclaredField("skipChapters").apply { isAccessible = true }
                .set(activity, listOf(MediaChapter(18_000, 21_000, "End credits")))
            call(activity, "hideControls")
            call(activity, "updateSkipSegment")
            val button = activity.findViewById<Button>(R.id.skipSegment)
            assertTrue(button.hasFocus())
            assertEquals("Passer le générique", button.text.toString())
            assertNull(button.background)
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        await("One OK must preserve the scene after the credits") { (field(activity, "player") as Player).currentPosition in 20_900..21_200 }
    }
    @Test fun automaticSkipFocusDoesNotTrapNavigationOrTakeFocusBackFromTheUser() = withPlayer { activity ->
        val seek = PlayerActivity::class.java.getDeclaredMethod("goToAbsolute", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        instrumentation.runOnMainSync { seek.invoke(activity, 3_500L) }
        await("Seek must finish") { (field(activity, "player") as Player).let {
            it.playbackState == Player.STATE_READY && it.currentPosition >= 3_400
        } }
        instrumentation.runOnMainSync {
            PlayerActivity::class.java.getDeclaredField("skipChapters").apply { isAccessible = true }
                .set(activity, listOf(MediaChapter(0, 10_000, "Opening")))
            call(activity, "hideControls")
            assertTrue(activity.findViewById<View>(R.id.skipSegment).hasFocus())
            (field(activity, "playerView") as View).requestFocusFromTouch()
            call(activity, "updateSkipSegment")
            assertFalse("Automatic focus is offered once, not on each tick", activity.findViewById<View>(R.id.skipSegment).hasFocus())
            activity.findViewById<View>(R.id.skipSegment).requestFocusFromTouch()
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(View.VISIBLE, (field(activity, "controlsOverlay") as View).visibility)
            call(activity, "hideControls")
            activity.findViewById<View>(R.id.skipSegment).requestFocusFromTouch()
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        await("Right must still seek when the prompt has focus and controls are hidden") {
            (field(activity, "player") as Player).currentPosition >= 13_000
        }
        instrumentation.runOnMainSync { assertFalse((field(activity, "player") as Player).playWhenReady) }
    }
    private fun showNext(activity: PlayerActivity) {
        val item = nextItem ?: field(activity, "item") as MediaItem
        PlayerActivity::class.java.getDeclaredMethod("showNextEpisodeDialog", MediaItem::class.java)
            .apply { isAccessible = true }.invoke(activity, item)
    }
    private fun seekBeforeEnd(activity: PlayerActivity, remainingMs: Long) {
        instrumentation.runOnMainSync {
            val player = field(activity, "player") as Player
            PlayerActivity::class.java.getDeclaredMethod("goToAbsolute", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, player.duration - remainingMs)
        }
        await("Seek before the end must finish") {
            (field(activity, "player") as Player).playbackState == Player.STATE_READY &&
                field(activity, "pendingSeekTargetMs") == null
        }
    }
    @Test fun countdownAppearsBeforeEndAndPauseDoesNotCutTheEpisodeOrAddAnotherFiveSeconds() = withPlayer { activity ->
        val next = nextItem
        assumeTrue("Requires a second episode", next != null)
        val currentId = (field(activity, "item") as MediaItem).id
        seekBeforeEnd(activity, 6_000)
        instrumentation.runOnMainSync {
            assertNull(field(activity, "nextEpisodeDialog"))
            (field(activity, "player") as Player).play()
        }
        await("Prompt must appear while the last five seconds are still playing") {
            (field(activity, "nextEpisodeDialog") as? Dialog)?.isShowing == true
        }
        instrumentation.runOnMainSync {
            val player = field(activity, "player") as Player
            assertEquals(Player.STATE_READY, player.playbackState)
            assertTrue(player.duration - player.currentPosition in 1..5_000)
            player.pause()
        }
        capture("tv-next-before-end.png")
        Thread.sleep(5_500)
        var remaining = 0L
        instrumentation.runOnMainSync {
            assertEquals(currentId, (field(activity, "item") as MediaItem).id)
            assertTrue((field(activity, "nextEpisodeDialog") as Dialog).isShowing)
            val player = field(activity, "player") as Player
            remaining = player.duration - player.currentPosition
            player.play()
        }
        val resumedAt = SystemClock.elapsedRealtime()
        await("The next episode must start at the media end") {
            (field(activity, "item") as MediaItem).id == next!!.id
        }
        assertTrue("No additional five-second wait after the end", SystemClock.elapsedRealtime() - resumedAt < remaining + 2_500)
    }
    @Test fun seekingBackDismissesTheEarlyPromptAndAutoplayOffDoesNotExitEarly() = withPlayer { activity ->
        assumeTrue("Requires a second episode", nextItem != null)
        seekBeforeEnd(activity, 4_500)
        instrumentation.runOnMainSync { (field(activity, "player") as Player).play() }
        await("Prompt must appear") { (field(activity, "nextEpisodeDialog") as? Dialog)?.isShowing == true }
        seekBeforeEnd(activity, 12_000)
        instrumentation.runOnMainSync {
            assertNull(field(activity, "nextEpisodeDialog"))
            (field(activity, "player") as Player).pause()
            PluxyApplication.setAutoPlayNextEpisode(context, false)
        }
        seekBeforeEnd(activity, 4_500)
        instrumentation.runOnMainSync { (field(activity, "player") as Player).play() }
        Thread.sleep(1_000)
        instrumentation.runOnMainSync {
            assertNull(field(activity, "nextEpisodeDialog"))
            assertFalse(activity.isFinishing)
            assertEquals(Player.STATE_READY, (field(activity, "player") as Player).playbackState)
        }
    }
    @Test fun nextEpisodeHasOnlyCancelAndOkReturnsImmediatelyToLibrary() = withPlayer { activity ->
        instrumentation.runOnMainSync {
            showNext(activity)
        }
        await("Cancel must receive focus when the prompt attaches") {
            (field(activity, "nextEpisodeDialog") as? Dialog)?.findViewById<View>(R.id.nextEpisodeStay)?.hasFocus() == true
        }
        instrumentation.runOnMainSync {
            val dialog = field(activity, "nextEpisodeDialog") as Dialog
            val cancel = dialog.findViewById<Button>(R.id.nextEpisodeStay)
            assertTrue(cancel.hasFocus())
            assertEquals("Annuler", cancel.text.toString())
        }
        capture("tv-next-episode.png")
        instrumentation.runOnMainSync {
            val dialog = field(activity, "nextEpisodeDialog") as Dialog
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            assertTrue(activity.isFinishing)
            assertFalse(dialog.isShowing)
        }
    }
    @Test fun noActionStartsNextEpisodeAfterFiveSeconds() = withPlayer { activity ->
        instrumentation.runOnMainSync { showNext(activity) }
        Thread.sleep(3_000)
        instrumentation.runOnMainSync { assertTrue((field(activity, "nextEpisodeDialog") as Dialog).isShowing) }
        await("Countdown must start playback and close its prompt") {
            field(activity, "nextEpisodeDialog") == null && (field(activity, "player") as? Player)?.playWhenReady == true &&
                (nextItem == null || (field(activity, "item") as MediaItem).id == nextItem!!.id)
        }
    }
}
