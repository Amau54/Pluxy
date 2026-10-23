package com.pluxy.tv.player

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import com.pluxy.tv.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Vertical adjustments stay on the video surface, away from buttons and the timeline. */
internal class PlayerTouchGestures(private val activity: Activity, private val target: View) {
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val accessibility = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val density = activity.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val gesture = VerticalPlayerSwipe(slop.toFloat())
    private var initialValue = 0f
    private var maximumVolume = 1
    private val feedback = TextView(activity).apply {
        textSize = 15f
        setTextColor(android.graphics.Color.WHITE)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setBackgroundResource(R.drawable.bg_glass_toolbar)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    private val dismiss = Runnable { feedback.visibility = View.GONE }
    private fun dp(value: Int) = (density * value).roundToInt()

    fun bind() {
        if (activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION) return
        activity.findViewById<FrameLayout>(R.id.playerRoot).addView(feedback,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { setMargins(dp(20), 0, dp(20), 0) })
        target.setOnTouchListener { view, event ->
            if (accessibility.isTouchExplorationEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.start(event.x, event.y, view.width.toFloat())
                    maximumVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    initialValue = if (gesture.right) brightness() else
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximumVolume
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) gesture.cancel()
                    gesture.move(event.x, event.y, view.height.toFloat())?.let { change ->
                        val value = (initialValue + change).coerceIn(if (gesture.right) .02f else 0f, 1f)
                        if (gesture.right) {
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                            show("Luminosité · ${(value * 100).roundToInt()} %")
                        } else if (audio.isVolumeFixed) show("Volume contrôlé par la sortie audio")
                        else {
                            try {
                                val volume = (value * maximumVolume).roundToInt()
                                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != volume)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                show("Volume · ${(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maximumVolume).roundToInt()} %")
                            } catch (_: SecurityException) { show("Volume limité par les réglages audio") }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { gesture.cancel(); true }
                MotionEvent.ACTION_UP -> {
                    if (gesture.isTap) view.performClick()
                    gesture.cancel()
                    true
                }
                else -> true
            }
        }
        target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { feedback.removeCallbacks(dismiss) }
        })
    }

    private fun brightness(): Float {
        val current = activity.window.attributes.screenBrightness
        return if (current >= 0) current else
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    }

    private fun show(label: String) {
        feedback.text = label
        feedback.visibility = View.VISIBLE
        feedback.removeCallbacks(dismiss)
        feedback.postDelayed(dismiss, 800)
    }
}

internal class VerticalPlayerSwipe(private val slop: Float) {
    private var x = 0f
    private var y = 0f
    private var canceled = false
    private var adjusting = false
    var right = false
        private set
    val isTap get() = !canceled && !adjusting

    fun start(x: Float, y: Float, width: Float) {
        this.x = x; this.y = y
        right = x >= width / 2
        canceled = false; adjusting = false
    }

    fun cancel() { canceled = true }

    fun move(x: Float, y: Float, height: Float): Float? {
        if (canceled || height <= 0) return null
        val dx = abs(x - this.x)
        val dy = this.y - y
        if (!adjusting) {
            if (abs(dy) <= slop && dx <= slop) return null
            if (abs(dy) <= dx) { cancel(); return null }
            adjusting = true
        }
        return dy / (height * .65f)
    }
}
