package com.pluxy.tv.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Surface d'illustration adaptative : affiche 2:3, capture vidéo 16:9 ou pochette carrée.
 * Le ratio suit la source réellement affichée afin de ne plus rogner une capture locale
 * horizontale dans un faux poster vertical.
 */
class PosterImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {

    private var heightRatio = 1.5f

    /** Ratio par défaut avant que la politique d'illustration connaisse la source. */
    fun setMediaKind(kind: String) {
        val next = if (kind == "photo" || kind == "audio") 1f else 1.5f
        setHeightRatio(next)
    }

    fun setArtworkAspect(aspect: ArtworkPolicy.Aspect) {
        setHeightRatio(aspect.heightToWidth)
    }

    private fun setHeightRatio(next: Float) {
        if (heightRatio == next) return
        heightRatio = next
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        if (w > 0) setMeasuredDimension(w, (w * heightRatio).toInt().coerceAtLeast(1))
    }
}
