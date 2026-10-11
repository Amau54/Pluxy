package com.pluxy.tv.ui

import android.content.Context
import com.pluxy.tv.PluxyApplication
import java.security.MessageDigest

data class FamilyProfile(val id: String, val name: String)

object FamilyProfileSelection {
    internal fun resolve(enabled: Boolean, saved: String?): String =
        if (enabled && saved != null && saved.matches(Regex("[0-9a-f]{32}"))) saved else "default"
    fun enabled(context: Context): Boolean = context.getSharedPreferences("family_profile_selection", Context.MODE_PRIVATE)
        .getBoolean("multiple_profiles", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("family_profile_selection", Context.MODE_PRIVATE).edit()
            .putBoolean("multiple_profiles", enabled).commit())
    }
    private fun key(server: String) = MessageDigest.getInstance("SHA-256")
        .digest(server.trimEnd('/').toByteArray()).joinToString("") { "%02x".format(it) }
    fun selected(server: String): String = runCatching {
        if (!enabled(PluxyApplication.instance)) return@runCatching "default"
        resolve(true, PluxyApplication.instance.getSharedPreferences("family_profile_selection", Context.MODE_PRIVATE)
            .getString(key(server), "default"))
    }.getOrDefault("default")
    fun name(server: String): String = runCatching {
        if (selected(server) == "default") return@runCatching "Général"
        PluxyApplication.instance.getSharedPreferences("family_profile_selection", Context.MODE_PRIVATE)
            .getString(key(server) + ":name", "Général") ?: "Général"
    }.getOrDefault("Général")
    fun select(context: Context, server: String, profile: FamilyProfile) {
        require(profile.id == "default" || profile.id.matches(Regex("[0-9a-f]{32}")))
        check(context.getSharedPreferences("family_profile_selection", Context.MODE_PRIVATE).edit()
            .putString(key(server), profile.id).putString(key(server) + ":name", profile.name).commit())
    }
}
