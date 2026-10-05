package com.pluxy.tv.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Applique la palette avant de créer les vues, y compris au retour des réglages. */
abstract class ThemedActivity : AppCompatActivity() {
    private lateinit var appliedTheme: AppearanceTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = AppearancePreferences.theme(this)
        setTheme(appliedTheme.style)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != AppearancePreferences.theme(this)) recreate()
    }
}
