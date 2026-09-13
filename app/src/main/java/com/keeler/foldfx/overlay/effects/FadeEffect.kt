package com.keeler.foldfx.overlay.effects

import android.graphics.Canvas
import android.graphics.Paint

/** Minimal effect: just fades the screen to black mid-fold. */
class FadeEffect : FoldEffect {

    override val id = "fade"
    override val displayName = "Fade"

    private val paint = Paint()

    override fun render(canvas: Canvas, progress: Float, width: Int, height: Int, intensity: Float) {
        val p = FoldEffect.activeProgress(progress) ?: return
        paint.color = 0xFF000000.toInt()
        // Never fully black: a hint of the screen stays visible even mid-fold.
        paint.alpha = FoldEffect.scaledAlpha(p, 200f, intensity, 235)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
