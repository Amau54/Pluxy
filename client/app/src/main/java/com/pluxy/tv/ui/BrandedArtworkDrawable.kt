package com.pluxy.tv.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Illustration de dernier recours, informative et lisible hors ligne.
 *
 * Les calculs coûteux dépendent uniquement des dimensions : ils sont donc préparés
 * dans [onBoundsChange] plutôt qu'à chaque frame de dessin.
 */
class BrandedArtworkDrawable(
    private val title: String,
    private val typeLabel: String,
    private val resolutionLabel: String?,
    private val spToPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private var gradient: LinearGradient? = null
    private var labelText = ""
    private var displayTitle = ""
    private var labelSize = 0f
    private var monogramSize = 0f
    private var titleSize = 0f
    private var drawableAlpha = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val radius = min(width, height) * 0.055f
        clip.reset()
        clip.addRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radius,
            radius,
            Path.Direction.CW,
        )

        val hueShift = ((title.hashCode().toLong() and 0x7fffffffL) % 28L).toInt()
        gradient = LinearGradient(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            Color.rgb(23 + hueShift / 3, 31 + hueShift / 4, 44 + hueShift / 3),
            Color.rgb(51 + hueShift / 3, 37 + hueShift / 4, 46 + hueShift / 3),
            Shader.TileMode.CLAMP,
        )
        labelText = listOfNotNull(typeLabel, resolutionLabel).joinToString("  •  ")
        labelSize = readableSize(
            desired = width * 0.055f,
            minimumSp = 12f,
            maximum = height * 0.15f,
        )
        monogramSize = min(width * 0.3f, height * 0.42f)
        titleSize = readableSize(
            desired = width * 0.075f,
            minimumSp = 16f,
            maximum = height * 0.2f,
        )
        configureTitlePaint()
        displayTitle = ellipsize(title.ifBlank { "Pluxy" }, width * 0.84f)
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        canvas.save()
        canvas.clipPath(clip)

        paint.alpha = drawableAlpha
        paint.shader = gradient
        canvas.drawRect(area, paint)
        paint.shader = null

        decorationPaint.color = Color.argb(effectiveAlpha(14), 255, 255, 255)
        canvas.drawCircle(
            area.right - area.width() * 0.08f,
            area.top + area.height() * 0.18f,
            area.width() * 0.42f,
            decorationPaint,
        )
        decorationPaint.color = Color.argb(effectiveAlpha(34), 0, 0, 0)
        canvas.drawCircle(
            area.left + area.width() * 0.08f,
            area.bottom - area.height() * 0.08f,
            area.width() * 0.5f,
            decorationPaint,
        )

        drawLabel(canvas, area)
        drawMonogram(canvas, area)
        drawTitle(canvas, area)
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, area: Rect) {
        textPaint.apply {
            color = Color.argb(effectiveAlpha(230), 255, 255, 255)
            typeface = BOLD_TYPEFACE
            textSize = labelSize
            letterSpacing = 0.12f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(
            labelText,
            area.left + area.width() * 0.08f,
            area.top + area.height() * 0.07f + labelSize,
            textPaint,
        )
    }

    private fun drawMonogram(canvas: Canvas, area: Rect) {
        val symbol = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P"
        textPaint.apply {
            color = Color.argb(effectiveAlpha(225), 255, 255, 255)
            typeface = BOLD_TYPEFACE
            textAlign = Paint.Align.CENTER
            textSize = monogramSize
            letterSpacing = 0f
        }
        val x = area.exactCenterX()
        val y = area.exactCenterY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(symbol, x, y, textPaint)
    }

    private fun drawTitle(canvas: Canvas, area: Rect) {
        configureTitlePaint()
        canvas.drawText(
            displayTitle,
            area.left + area.width() * 0.08f,
            area.bottom - area.height() * 0.09f,
            textPaint,
        )
    }

    private fun configureTitlePaint() {
        textPaint.apply {
            color = Color.argb(drawableAlpha, 255, 255, 255)
            typeface = BOLD_TYPEFACE
            textSize = titleSize
            letterSpacing = 0f
            textAlign = Paint.Align.LEFT
        }
    }

    private fun readableSize(desired: Float, minimumSp: Float, maximum: Float): Float {
        val minimum = minimumSp * spToPx.coerceAtLeast(1f)
        return desired.coerceAtLeast(minimum).coerceAtMost(maximum.coerceAtLeast(minimum))
    }

    private fun effectiveAlpha(sourceAlpha: Int): Int =
        sourceAlpha * drawableAlpha / 255

    private fun ellipsize(value: String, maxWidth: Float): String {
        if (textPaint.measureText(value) <= maxWidth) return value
        var shortened = value
        while (shortened.length > 1 && textPaint.measureText("$shortened…") > maxWidth) {
            shortened = shortened.dropLast(1)
        }
        return "$shortened…"
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        decorationPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in the Android framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val BOLD_TYPEFACE: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}
