package com.pluxy.tv.ui

enum class SettingsCategory(val key: String, val label: String) {
    PLAYBACK("playback", "Lecture"),
    APPEARANCE("appearance", "Apparence"),
    LIBRARY("library", "Bibliothèque"),
    CONNECTION("connection", "Connexion"),
    PROFILES("profiles", "Profils"),
    ACCESSIBILITY("accessibility", "Accessibilité"),
    APPLICATION("application", "Application");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: PLAYBACK
    }
}
