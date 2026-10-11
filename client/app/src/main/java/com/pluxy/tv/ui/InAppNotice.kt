package com.pluxy.tv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pluxy.tv.R
import java.util.WeakHashMap

object InAppNotice {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1
    private val current = WeakHashMap<Activity, TextView>()
    fun makeText(context: Context, text: Int, length: Int) = makeText(context, context.getString(text), length)
    fun makeText(context: Context, text: CharSequence, length: Int) = Notice(context, text, length)
    class Notice(private val context: Context, private val text: CharSequence, private val length: Int) {
        fun show() {
            val activity = context as? Activity ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            activity.runOnUiThread {
                val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return@runOnUiThread
                current.remove(activity)?.let(root::removeView)
                val density = activity.resources.displayMetrics.density
                fun dp(n: Int) = (n * density).toInt()
                fun color(attr: Int): Int = TypedValue().let { activity.theme.resolveAttribute(attr, it, true); it.data }
                val view = TextView(activity).apply {
                    text = this@Notice.text
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(color(R.attr.pluxyTextPrimary))
                    setPadding(dp(16), dp(11), dp(16), dp(11))
                    maxWidth = (root.width - dp(48)).coerceAtLeast(dp(200)).coerceAtMost(dp(420))
                    maxLines = 4
                    isFocusable = false
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(ColorUtils.setAlphaComponent(color(R.attr.pluxyBgElev), 242))
                        setStroke(dp(1).coerceAtLeast(1), ColorUtils.setAlphaComponent(color(R.attr.pluxyAccent), 80))
                    }
                    elevation = dp(8).toFloat()
                }
                root.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(24); marginEnd = dp(24)
                })
                current[activity] = view
                view.postDelayed({
                    root.removeView(view)
                    if (current[activity] === view) current.remove(activity)
                }, if (length == LENGTH_LONG) 5_000 else 3_000)
            }
        }
    }
}
