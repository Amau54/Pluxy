package com.pluxy.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import com.pluxy.tv.R

/** The clipping path also works on TV drivers that ignore background outlines. */
class RoundedArtworkFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val mask = Path()
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = resources.getDimension(R.dimen.radius_md)
        mask.reset()
        mask.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
    }
    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipPath(mask)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }
}
