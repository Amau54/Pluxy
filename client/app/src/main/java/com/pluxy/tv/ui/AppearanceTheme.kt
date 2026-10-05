package com.pluxy.tv.ui

import android.content.Context
import com.pluxy.tv.R

enum class AppearanceTheme(val key: String, val label: String, val description: String, val style: Int) {
    CORAL("coral", "Corail", "Graphite et lumière chaude", R.style.Theme_Pluxy),
    OCEAN("ocean", "Océan", "Verre bleuté et reflets cyan", R.style.Theme_Pluxy_Ocean),
    MIDNIGHT("midnight", "Minuit", "Noir profond et reflets discrets", R.style.Theme_Pluxy_Midnight);

    companion object {
        fun fromKey(key: String?): AppearanceTheme = entries.firstOrNull { it.key == key } ?: CORAL
    }
}

object AppearancePreferences {
    private const val PREFS = "pluxy_appearance"
    private const val KEY_THEME = "theme"

    fun theme(context: Context): AppearanceTheme = AppearanceTheme.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null),
    )

    fun setTheme(context: Context, theme: AppearanceTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, theme.key).apply()
    }
}
