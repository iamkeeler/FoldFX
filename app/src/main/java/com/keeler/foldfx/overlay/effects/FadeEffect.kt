package com.keeler.foldfx.overlay.effects

import android.graphics.Canvas
import android.graphics.Paint

/** Minimal effect: just fades the screen to black mid-fold. */
class FadeEffect : FoldEffect {

    override val id = "fade"
    override val displayName = "Fade"

    private val paint = Paint()

    override fun render(canvas: Canvas, progress: Float, width: Int, height: Int, intensity: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return
        paint.color = 0xFF000000.toInt()
        paint.alpha = (p * 200 * intensity).toInt().coerceIn(0, 235)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
