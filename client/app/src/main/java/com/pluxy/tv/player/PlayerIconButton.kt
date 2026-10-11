package com.pluxy.tv.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.pluxy.tv.R

/** Le même tracé et les mêmes dimensions au repos, au survol et au focus. */
class PlayerIconButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatImageButton(context, attrs) {
    enum class Icon { PLAY, PAUSE, RESTART, BACK_TEN, FORWARD_TEN, AUDIO, SUBTITLES, MORE, CLOSE }
    private var icon: Icon? = null
    private var glyph: Drawable? = null
    private var emphasis = 0f
    private var focusAnimation: ValueAnimator? = null
    private var glow: Bitmap? = null
    private var glowSize = 0
    private val glowOffset = IntArray(2)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val contrast = com.pluxy.tv.PluxyApplication.playerFocusContrast(context)
    private val normalColor = ColorUtils.blendARGB(themeColor(R.attr.pluxyTextPrimary), themeColor(R.attr.pluxyBg), if (contrast) 0.45f else 0.1f)
    private val activeColor = if (contrast) Color.WHITE else themeColor(R.attr.pluxyAccentBright)

    init {
        background = null
        backgroundTintList = null
        imageTintList = null
        setPadding(0, 0, 0, 0)
        minimumWidth = (48 * resources.displayMetrics.density).toInt()
        minimumHeight = minimumWidth
        isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setIcon(when (id) {
            R.id.btnRestart -> Icon.RESTART
            R.id.btnRewind -> Icon.BACK_TEN
            R.id.btnForward -> Icon.FORWARD_TEN
            R.id.btnTracks -> Icon.AUDIO
            R.id.btnSubtitles -> Icon.SUBTITLES
            R.id.btnGear -> Icon.MORE
            R.id.btnExitPlayback -> Icon.CLOSE
            else -> Icon.PLAY
        })
    }

    fun setIcon(value: Icon) {
        if (icon == value) return
        icon = value
        val resource = when (value) {
            Icon.PLAY -> R.drawable.ic_player_symbol_play
            Icon.PAUSE -> R.drawable.ic_player_symbol_pause
            Icon.RESTART -> R.drawable.ic_player_symbol_restart
            Icon.BACK_TEN -> R.drawable.ic_player_symbol_back
            Icon.FORWARD_TEN -> R.drawable.ic_player_symbol_forward
            Icon.AUDIO -> R.drawable.ic_player_symbol_audio
            Icon.SUBTITLES -> R.drawable.ic_player_symbol_subtitles
            Icon.MORE -> R.drawable.ic_player_symbol_more
            Icon.CLOSE -> R.drawable.ic_player_symbol_close
        }
        glyph = AppCompatResources.getDrawable(context, resource)?.mutate()
        glow = null
        glowSize = 0
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val target = if (isEnabled && (isFocused || isPressed || isHovered)) 1f else 0f
        focusAnimation?.cancel()
        if (!isAttachedToWindow) { emphasis = target; return }
        focusAnimation = ValueAnimator.ofFloat(emphasis, target).apply {
            duration = 120
            addUpdateListener { emphasis = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        focusAnimation?.cancel()
        glow = null
        glowSize = 0
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = glyph ?: return
        val primary = icon == Icon.PLAY || icon == Icon.PAUSE
        val size = minOf((if (primary) 30f else 28f) * resources.displayMetrics.density,
            width * 0.65f, height * 0.65f).toInt()
        if (size <= 0) return
        val left = (width - size) / 2
        val top = (height - size) / 2
        if (emphasis > 0f) {
            if (glow == null || glowSize != size) {
                val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, size, size)
                DrawableCompat.setTint(drawable, Color.WHITE)
                drawable.draw(Canvas(mask))
                val blur = Paint().apply {
                    maskFilter = BlurMaskFilter(2f * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
                }
                glow = mask.extractAlpha(blur, glowOffset)
                glowSize = size
                mask.recycle()
            }
            glowPaint.color = activeColor
            glowPaint.alpha = ((if (contrast) 112 else 72) * emphasis).toInt()
            glow?.let { canvas.drawBitmap(it, (left + glowOffset[0]).toFloat(),
                (top + glowOffset[1]).toFloat(), glowPaint) }
        }
        drawable.setBounds(left, top, left + size, top + size)
        DrawableCompat.setTint(drawable, if (isEnabled) ColorUtils.blendARGB(normalColor, activeColor, emphasis) else Color.GRAY)
        drawable.draw(canvas)
    }

    private fun themeColor(attribute: Int): Int = context.obtainStyledAttributes(intArrayOf(attribute))
        .let { attributes -> try { attributes.getColor(0, Color.WHITE) } finally { attributes.recycle() } }
}
