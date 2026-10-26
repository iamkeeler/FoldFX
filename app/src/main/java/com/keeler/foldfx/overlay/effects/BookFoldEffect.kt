package com.keeler.foldfx.overlay.effects

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Defocus the actual page through compositor blur, with a quiet diffusion veil.
 * Both paints follow progress all the way to zero, so settling or reversing the
 * hinge never leaves a fixed-strength highlight or a hard spine over the page.
 */
class BookFoldEffect : FoldEffect {

    override val id = "book_fold"
    override val displayName = "Book Fold"

    private val shade = Paint().apply { color = 0xFF000000.toInt() }
    private val diffusion = Paint().apply { color = 0xFFFFFFFF.toInt() }

    override fun render(canvas: Canvas, progress: Float, width: Int, height: Int, intensity: Float) {
        val p = FoldEffect.activeProgress(progress) ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // A little contrast loss reinforces the optical defocus without hiding the page.
        shade.alpha = FoldEffect.scaledAlpha(p, 18f, intensity, 30)
        canvas.drawRect(0f, 0f, w, h, shade)
        diffusion.alpha = FoldEffect.scaledAlpha(p, 14f, intensity, 24)
        canvas.drawRect(0f, 0f, w, h, diffusion)
    }
}
