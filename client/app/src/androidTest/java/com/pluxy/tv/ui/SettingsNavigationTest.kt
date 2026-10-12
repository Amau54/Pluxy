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
            SettingsCategory.PLAYBACK to "Mode de lecture",
            SettingsCategory.APPEARANCE to "Thème",
            SettingsCategory.LIBRARY to "Personnaliser l’accueil",
            SettingsCategory.CONNECTION to "Changer de serveur",
            SettingsCategory.PROFILES to "Activer plusieurs profils",
            SettingsCategory.ACCESSIBILITY to "Focus du lecteur plus contrasté",
            SettingsCategory.APPLICATION to "Mises à jour",
        )
        try {
            for ((category, setting) in expectedControls) {
                instrumentation.runOnMainSync {
                    activity.window.decorView.findViewWithTag<View>("category:${category.key}").performClick()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity.window.decorView
                    assertTrue(root.findViewWithTag<View>("category:${category.key}").isSelected)
                    assertNotNull(root.findViewWithTag<View>("setting:$setting"))
                    assertNull(root.findViewWithTag<View>("setting:Options"))
                    assertFalse(activity.isFinishing)
                }
            }
            assertEquals(0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
