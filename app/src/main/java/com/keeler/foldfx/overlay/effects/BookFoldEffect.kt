package com.keeler.foldfx.overlay.effects

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.min

/**
 * The book-style foldable mimic: the compositor blurs everything behind the
 * overlay (see [com.keeler.foldfx.overlay.FoldOverlayManager]) while this
 * draws a darkening scrim, a light sweep travelling across the "folding
 * glass", and a soft glow along the spine crease.
 *
 * Zero-allocation render path: the sweep gradient is built once in unit
 * coordinates and moved/scaled with the canvas matrix, so a fold at 120 Hz
 * never touches the allocator — even with inner + outer displays rendering
 * the same shared effect instance.
 */
class BookFoldEffect : FoldEffect {

    override val id = "book_fold"
    override val displayName = "Book Fold"

    private val scrimPaint = Paint()
    private val sweepPaint = Paint()
    private val spinePaint = Paint()

    /**
     * Gradient in unit coordinates (-1 -> +1); the canvas matrix both moves
     * and scales the band, so one shader serves every display size with zero
     * per-frame allocation — even with inner + outer displays rendering.
     */
    private fun sweepShader(): Shader {
        if (sweepPaint.shader == null) {
            sweepPaint.shader = LinearGradient(
                -1f, 0f, 1f, 0f,
                intArrayOf(0x00000000, 0x55FFFFFF, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        return sweepPaint.shader!!
    }

    override fun render(canvas: Canvas, progress: Float, width: Int, height: Int, intensity: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Darken as the fold deepens.
        scrimPaint.shader = null
        scrimPaint.color = 0xFF000000.toInt()
        scrimPaint.alpha = (p * 90 * intensity).toInt().coerceIn(0, 160)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // Light sweep travelling with the fold. The band is drawn in unit-x
        // and positioned by the canvas matrix: scale sizes it, skew gives the
        // tilted-glass slant, translate moves it across the screen.
        val bandW = w * 0.28f
        val sweepX = w * (0.15f + 0.7f * p)
        sweepShader()
        val checkpoint = canvas.save()
        canvas.translate(sweepX, 0f)
        canvas.skew(-0.18f, 0f)
        canvas.scale(bandW, 1f)
        canvas.drawRect(-1f, -h * 0.2f, 1f, h * 1.2f, sweepPaint)
        canvas.restoreToCount(checkpoint)

        // Crease glow along the spine, strongest mid-fold.
        spinePaint.shader = null
        spinePaint.color = 0xFFFFFFFF.toInt()
        spinePaint.alpha = (p * 70 * intensity).toInt().coerceIn(0, 110)
        val spineW = min(w, h) * 0.012f + 2f
        canvas.drawRect(w / 2f - spineW, 0f, w / 2f + spineW, h, spinePaint)
    }
}
