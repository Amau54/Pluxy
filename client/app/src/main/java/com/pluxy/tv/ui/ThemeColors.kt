package com.pluxy.tv.ui

import android.content.Context
import android.util.TypedValue
import androidx.annotation.ColorRes
import com.pluxy.tv.R

// Android 9 loses a radial gradient's literal radius when applying themed colors.
// Select a complete palette drawable instead, keeping both the colors and radius.
fun Context.ambientBackgroundResource(): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(R.attr.pluxyAmbientBackground, value, true) && value.resourceId != 0) {
        "Fond Pluxy manquant"
    }
    return value.resourceId
}

/** Les vues créées en Kotlin suivent la même palette que les layouts. */
fun Context.pluxyColor(@ColorRes resource: Int): Int {
    val attribute = when (resource) {
        R.color.bg -> R.attr.pluxyBg
        R.color.bg_elev -> R.attr.pluxyBgElev
        R.color.surface -> R.attr.pluxySurface
        R.color.surface_variant -> R.attr.pluxySurfaceVariant
        R.color.surface_focus -> R.attr.pluxySurfaceFocus
        R.color.glass_top -> R.attr.pluxyGlassTop
        R.color.glass_bottom -> R.attr.pluxyGlassBottom
        R.color.glass_focus_top -> R.attr.pluxyGlassFocusTop
        R.color.glass_focus_bottom -> R.attr.pluxyGlassFocusBottom
        R.color.glass_glint -> R.attr.pluxyGlassGlint
        R.color.glass_glint_trans -> R.attr.pluxyGlassGlintTrans
        R.color.ambient_coral -> R.attr.pluxyAmbientCoral
        R.color.ambient_coral_trans -> R.attr.pluxyAmbientCoralTrans
        R.color.ambient_rose -> R.attr.pluxyAmbientRose
        R.color.ambient_rose_trans -> R.attr.pluxyAmbientRoseTrans
        R.color.glow_edge -> R.attr.pluxyGlowEdge
        R.color.accent -> R.attr.pluxyAccent
        R.color.accent_bright -> R.attr.pluxyAccentBright
        R.color.accent_dim -> R.attr.pluxyAccentDim
        R.color.info -> R.attr.pluxyInfo
        R.color.success -> R.attr.pluxySuccess
        R.color.text_primary -> R.attr.pluxyTextPrimary
        R.color.text_secondary -> R.attr.pluxyTextSecondary
        R.color.text_tertiary -> R.attr.pluxyTextTertiary
        R.color.text_on_accent -> R.attr.pluxyTextOnAccent
        R.color.border -> R.attr.pluxyBorder
        R.color.border_subtle -> R.attr.pluxyBorderSubtle
        R.color.border_focus -> R.attr.pluxyBorderFocus
        R.color.scrim_strong -> R.attr.pluxyScrimStrong
        R.color.bg_trans -> R.attr.pluxyBgTrans
        R.color.bg_full -> R.attr.pluxyBgFull
        R.color.player_unplayed -> R.attr.pluxyPlayerUnplayed
        R.color.player_buffered -> R.attr.pluxyPlayerBuffered
        else -> return getColor(resource)
    }
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) { "Palette Pluxy incomplète" }
    return if (value.resourceId != 0) getColor(value.resourceId) else value.data
}
