package com.pluxy.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LibraryNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        capture("tv-library-failure.png")
        fail(message + " · " + nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }.joinToString(" | "))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList()
        else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun click(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var node: AccessibilityNodeInfo? = null
        while (node == null && System.currentTimeMillis() < deadline) {
            node = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true && it.isClickable }
            if (node == null) Thread.sleep(50)
        }
        if (node == null) capture("tv-library-missing-action.png")
        assertNotNull("Missing action: $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    @Test fun railFollowsVisibleDestinationsAfterTheFirstCatalogueLoadAndRefresh() {
        val server = InstrumentationRegistry.getArguments().getString("library_test_url").orEmpty()
        assumeTrue("Requires isolated catalogue fixture", server.isNotBlank())
        PluxyApplication.setServer(context, server, "Test rail")
        PluxyApplication.setLibraryTab(context, "home")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("All destinations must load") { activity.findViewById<View>(R.id.tabAudio).isShown }
            val order = listOf(R.id.tabHome, R.id.tabMovies, R.id.tabSeries, R.id.tabVideos, R.id.tabPhotos, R.id.tabAudio, R.id.settings)
            repeat(2) {
                instrumentation.runOnMainSync { activity.findViewById<View>(R.id.tabHome).requestFocusFromTouch() }
                for (id in order.drop(1)) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Down must follow the visible menu, target=$id") { activity.findViewById<View>(id).hasFocus() }
                }
                for (id in order.dropLast(1).reversed()) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    await("Up must follow the visible menu, target=$id") { activity.findViewById<View>(id).hasFocus() }
                }
                instrumentation.runOnMainSync { activity.findViewById<View>(R.id.tabHome).performClick() }
                instrumentation.waitForIdleSync()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun emptySeriesSearchDoesNotSilentlyFilterTheMoviesTab() {
        val server = InstrumentationRegistry.getArguments().getString("library_test_url").orEmpty()
        assumeTrue("Requires isolated catalogue fixture", server.isNotBlank())
        PluxyApplication.setServer(context, server, "Test filtre invisible")
        PluxyApplication.setLibraryTab(context, "series")
        for (kind in listOf("home", "movie", "series")) {
            LibraryPreferences.saveQuery(context, TvLibraryContent.Query(kind, sort = "title"))
            LibraryPreferences.saveView(context, kind, "library")
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("Series must load") { activity.findViewById<View>(R.id.tabAudio).isShown }
            instrumentation.runOnMainSync { activity.findViewById<View>(R.id.search).performClick() }
            instrumentation.waitForIdleSync()
            click("Rechercher")
            instrumentation.runOnMainSync { activity.findViewById<View>(R.id.tabMovies).performClick() }
            await("Unfiltered movies must remain visible after an empty series search") {
                val grid = activity.findViewById<RecyclerView>(R.id.grid)
                grid.adapter is LibraryAdapter && grid.adapter!!.itemCount == 2 &&
                    activity.findViewById<View>(R.id.empty).visibility != View.VISIBLE
            }
            capture("tv-movies-without-hidden-filter.png")
            instrumentation.runOnMainSync {
                LibraryPreferences.saveQuery(context, TvLibraryContent.Query("movie", year = 1999))
                activity.findViewById<View>(R.id.tabMovies).performClick()
            }
            await("A real filter must still explain the empty result") {
                activity.findViewById<TextView>(R.id.empty).let { it.isShown && it.text.contains("filtres") } &&
                    activity.findViewById<Button>(R.id.emptyAction).text == "Réinitialiser les filtres"
            }
            instrumentation.runOnMainSync { activity.findViewById<View>(R.id.emptyAction).performClick() }
            await("Reset must restore the movies") {
                activity.findViewById<RecyclerView>(R.id.grid).adapter?.itemCount == 2 &&
                    activity.findViewById<View>(R.id.empty).visibility != View.VISIBLE
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun tvSettingsAreBelowMusicAndSearchUsesTheOpenCategoryWithIntegratedFilters() {
        val server = InstrumentationRegistry.getArguments().getString("library_test_url").orEmpty()
        assumeTrue("Requires isolated catalogue fixture", server.isNotBlank())
        PluxyApplication.setServer(context, server, "Test bibliothèque")
        PluxyApplication.setLibraryTab(context, "movie")
        LibraryPreferences.saveQuery(context, TvLibraryContent.Query("movie", sort = "title"))
        LibraryPreferences.saveQuery(context, TvLibraryContent.Query("series", sort = "title"))
        LibraryPreferences.saveQuery(context, TvLibraryContent.Query("home", sort = "title"))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("All fixture destinations must load") { activity.findViewById<View>(R.id.tabAudio).visibility == View.VISIBLE }
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                val settings = activity.findViewById<View>(R.id.settings)
                assertNotSame(activity.findViewById<View>(R.id.topBar), settings.parent)
                val settingsPosition = IntArray(2); val musicPosition = IntArray(2)
                settings.getLocationOnScreen(settingsPosition)
                val music = activity.findViewById<View>(R.id.tabAudio)
                music.getLocationOnScreen(musicPosition)
                assertTrue(settingsPosition[1] >= musicPosition[1] + music.height)
                assertTrue(settingsPosition[0] < activity.resources.displayMetrics.widthPixels / 3)
                music.requestFocusFromTouch()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down from Music must reach Settings") { activity.findViewById<View>(R.id.settings).hasFocus() }
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.tabMovies).performClick()
            }
            capture("tv-library-navigation.png")
            val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.findViewById<View>(R.id.settings).performClick() }
            val settings = monitor.waitForActivityWithTimeout(5000) as? SettingsActivity
            assertNotNull(settings)
            instrumentation.runOnMainSync {
                settings!!.window.decorView.findViewWithTag<View>("category:appearance").performClick()
                assertNotNull(settings.window.decorView.findViewWithTag<View>("setting:Présentation"))
            }
            capture("tv-library-settings.png")
            instrumentation.runOnMainSync { settings!!.finish() }
            instrumentation.removeMonitor(monitor)
            await("Library must resume") { activity.hasWindowFocus() }
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.tabSeries).performClick()
                activity.findViewById<View>(R.id.search).performClick()
            }
            instrumentation.waitForIdleSync()
            var visible = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue(visible.any { it.text?.toString() == "Rechercher dans les séries" })
            assertTrue(visible.none { it.className?.toString()?.endsWith("RadioButton") == true })
            click("Lecture · Tous")
            click("Non vus")
            visible = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            val input = visible.first { it.className?.toString()?.endsWith("EditText") == true }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Film")
            }))
            capture("tv-library-search.png")
            click("Rechercher")
            await("Films must not leak into a series search") {
                val scope = MainActivity::class.java.getDeclaredField("searchScope").apply { isAccessible = true }.get(activity)
                scope == "series" && LibraryPreferences.query(context, "series").watchedFilter == "unwatched" &&
                    activity.findViewById<TextView>(R.id.empty).let { it.visibility == View.VISIBLE && it.text.contains("Aucun") }
            }
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.tabHome).performClick()
                activity.findViewById<View>(R.id.search).performClick()
            }
            instrumentation.waitForIdleSync()
            click("Lecture · Tous")
            click("Non vus")
            val homeInput = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                .first { it.className?.toString()?.endsWith("EditText") == true }
            homeInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Film")
            })
            click("Rechercher")
            await("Home search must retain its combined filters") {
                val query = MainActivity::class.java.getDeclaredMethod("currentQuery").apply { isAccessible = true }
                    .invoke(activity) as TvLibraryContent.Query
                query.searchScope == "all" && query.watchedFilter == "unwatched" &&
                    query.search == "Film" && activity.findViewById<View>(R.id.empty).visibility == View.GONE
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun upFromTheFirstResumeShelfAlwaysReachesTheToolbarInsteadOfHome() {
        val server = InstrumentationRegistry.getArguments().getString("library_test_url").orEmpty()
        assumeTrue("Requires isolated catalogue fixture", server.isNotBlank())
        PluxyApplication.setServer(context, server, "Test navigation")
        PluxyApplication.setLibraryTab(context, "home")
        LibraryPreferences.saveQuery(context, TvLibraryContent.Query("home"))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val grid = activity.findViewById<RecyclerView>(R.id.grid)
        try {
            await("The first home shelf must be Continue watching") {
                (grid.adapter as? HomeShelfAdapter)?.currentList?.firstOrNull()?.id == "continue" &&
                    grid.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<RecyclerView>(R.id.shelfCards)?.childCount?.let { it > 0 } == true
            }
            repeat(3) {
                instrumentation.runOnMainSync {
                    val cards = grid.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<RecyclerView>(R.id.shelfCards)
                    val card = cards.getChildAt(0)
                    assertTrue(card.requestFocusFromTouch())
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Up must reach Search, including repeated trips from the first shelf") {
                    activity.findViewById<View>(R.id.search).hasFocus()
                }
                instrumentation.runOnMainSync { assertFalse(activity.findViewById<View>(R.id.tabHome).hasFocus()) }
            }
            capture("tv-home-up-search.png")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun bannerKeepsArtworkInsideTheLauncherSafeMargins() {
        val banner = BitmapFactory.decodeResource(context.resources, R.drawable.ic_banner)
        assertEquals(320, banner.width); assertEquals(180, banner.height)
        for (x in 0 until banner.width) for (y in 0 until banner.height) {
            val color = banner.getPixel(x, y)
            if (android.graphics.Color.red(color) > 60 || android.graphics.Color.green(color) > 60 || android.graphics.Color.blue(color) > 60)
                assertTrue("Brand pixels must stay clear of cropped edges", x in 32..287 && y in 24..155)
        }
        banner.recycle()
    }
}
