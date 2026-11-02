package com.pluxy.tv.ui

import android.content.Intent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun categoriesStayOnOneScreenWithoutAnOptionsActivityStack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        val expectedControls = linkedMapOf(
            SettingsCategory.PLAYBACK to listOf("Mode de lecture"),
            SettingsCategory.APPEARANCE to listOf("Thème", "Densité", "Personnaliser l’accueil"),
            SettingsCategory.GENERAL to listOf("Changer de serveur", "Activer plusieurs profils",
                "Focus du lecteur plus contrasté", "Mises à jour", "Mode de l’application"),
        )
        try {
            for ((category, settings) in expectedControls) {
                instrumentation.runOnMainSync {
                    activity.window.decorView.findViewWithTag<View>("category:${category.key}").performClick()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity.window.decorView
                    assertTrue(root.findViewWithTag<View>("category:${category.key}").isSelected)
                    settings.forEach { setting -> assertNotNull(root.findViewWithTag<View>("setting:$setting")) }
                    listOf("library", "connection", "profiles", "accessibility", "application").forEach {
                        assertNull(root.findViewWithTag<View>("category:$it"))
                    }
                    assertNull(root.findViewWithTag<View>("setting:Options"))
                    assertFalse(activity.isFinishing)
                }
                val television = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                    android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                if (television) {
                    instrumentation.runOnMainSync {
                        val button = activity.window.decorView.findViewWithTag<View>("category:${category.key}")
                        button.requestFocusFromTouch()
                    }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        val button = activity.window.decorView.findViewWithTag<View>("category:${category.key}")
                        assertTrue("Right from ${category.key}: ${activity.currentFocus?.tag}",
                            activity.currentFocus?.tag.toString().startsWith("setting:"))
                        assertEquals(button.id, activity.currentFocus?.nextFocusLeftId)
                    }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        val button = activity.window.decorView.findViewWithTag<View>("category:${category.key}")
                        assertTrue(button.hasFocus())
                    }
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    java.io.File(activity.getExternalFilesDir(null), "settings-${category.key}.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
                if (television && category == SettingsCategory.GENERAL) {
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    repeat(5) {
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                        instrumentation.waitForIdleSync()
                    }
                    Thread.sleep(300)
                    instrumentation.runOnMainSync {
                        assertEquals("setting:Mode de l’application", activity.currentFocus?.tag)
                        val visible = android.graphics.Rect()
                        assertTrue(activity.currentFocus!!.getGlobalVisibleRect(visible))
                        assertTrue(visible.height() >= activity.currentFocus!!.height - 2)
                        assertTrue(visible.bottom <= activity.resources.displayMetrics.heightPixels -
                            (16 * activity.resources.displayMetrics.density).toInt())
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                        java.io.File(activity.getExternalFilesDir(null), "settings-general-bottom.png").outputStream().use {
                            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        image.recycle()
                    }
                }
            }
            assertEquals(0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
