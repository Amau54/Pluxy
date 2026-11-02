package com.pluxy.tv.ui

enum class SettingsCategory(val key: String, val label: String) {
    PLAYBACK("playback", "Lecture"),
    APPEARANCE("appearance", "Affichage"),
    GENERAL("general", "Général");

    companion object {
        fun fromKey(key: String?) = when (key) {
            "library" -> APPEARANCE
            "connection", "profiles", "accessibility", "application" -> GENERAL
            else -> entries.firstOrNull { it.key == key } ?: PLAYBACK
        }
    }
}
