package com.pluxy.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.tv.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBackgroundTest {
    @Test
    fun allPalettesInflateRenderAndReapplyTheirTheme() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        for (style in listOf(R.style.Theme_Pluxy, R.style.Theme_Pluxy_Ocean, R.style.Theme_Pluxy_Midnight)) {
            val context = ContextThemeWrapper(target, style)
            repeat(2) { // Exercise cached drawable loading too.
                val drawable = context.getDrawable(context.ambientBackgroundResource())!!.mutate() as LayerDrawable
                drawable.applyTheme(context.theme)
                assertArrayEquals(
                    intArrayOf(context.pluxyColor(R.color.bg_elev), context.pluxyColor(R.color.bg), context.pluxyColor(R.color.bg_full)),
                    (drawable.getDrawable(0) as GradientDrawable).colors,
                )
                assertArrayEquals(
                    intArrayOf(context.pluxyColor(R.color.ambient_coral), context.pluxyColor(R.color.ambient_coral_trans)),
                    (drawable.getDrawable(1) as GradientDrawable).colors,
                )
                assertArrayEquals(
                    intArrayOf(context.pluxyColor(R.color.ambient_rose), context.pluxyColor(R.color.ambient_rose_trans)),
                    (drawable.getDrawable(2) as GradientDrawable).colors,
                )
                val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.draw(Canvas(bitmap))
                    val density = context.resources.displayMetrics.density
                    assertEquals(310f * density, (drawable.getDrawable(1) as GradientDrawable).gradientRadius, 0.1f)
                    assertEquals(280f * density, (drawable.getDrawable(2) as GradientDrawable).gradientRadius, 0.1f)
                    assertTrue(bitmap.getPixel(320, 180) ushr 24 > 0)
                } finally {
                    bitmap.recycle()
                }
                // The same theme background also needs to work through layout inflation.
                val view = LayoutInflater.from(context).inflate(R.layout.activity_server_setup, null)
                assertTrue(view.background is LayerDrawable)
            }
        }
    }

    @Test
    fun freshModeScreenStartsWithoutCrashing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("pluxy_application_mode", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = instrumentation.startActivitySync(
            Intent(context, ModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            instrumentation.waitForIdleSync()
            assertFalse(activity.isFinishing)
            assertTrue(activity.findViewById<android.view.View>(android.R.id.content).isShown)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
