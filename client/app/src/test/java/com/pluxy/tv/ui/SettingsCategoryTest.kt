package com.pluxy.tv.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsCategoryTest {
    @Test fun oldEntryPointsOpenTheirNewSection() {
        assertEquals(SettingsCategory.APPEARANCE, SettingsCategory.fromKey("library"))
        for (key in listOf("connection", "profiles", "accessibility", "application", "general"))
            assertEquals(SettingsCategory.GENERAL, SettingsCategory.fromKey(key))
        assertEquals(SettingsCategory.PLAYBACK, SettingsCategory.fromKey(null))
        assertEquals(SettingsCategory.PLAYBACK, SettingsCategory.fromKey("unknown"))
    }
}
