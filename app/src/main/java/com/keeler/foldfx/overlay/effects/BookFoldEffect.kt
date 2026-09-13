package com.keeler.foldfx.overlay.effects

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.min

/**
 * The iPhone-Duo-style mimic: the compositor blurs everything behind the
 * overlay (see [com.keeler.foldfx.overlay.FoldOverlayManager]) while this
 * draws a darkening scrim, a light sweep travelling across the "folding
 * glass", and a soft glow along the spine crease.
 */
class BookFoldEffect : FoldEffect {

    override val id = "book_fold"
    override val displayName = "Book Fold"

    private val scrimPaint = Paint()
    private val sweepPaint = Paint()
    private val spinePaint = Paint()

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

        // Light sweep travelling with the fold, skewed for a tilted-glass feel.
        val sweepX = w * (0.15f + 0.7f * p)
        val bandW = w * 0.28f
        sweepPaint.shader = LinearGradient(
            sweepX - bandW, 0f, sweepX + bandW, 0f,
            intArrayOf(0x00000000, 0x55FFFFFF, 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val checkpoint = canvas.save()
        canvas.skew(-0.18f, 0f)
        canvas.drawRect(sweepX - bandW, -h * 0.2f, sweepX + bandW, h * 1.2f, sweepPaint)
        canvas.restoreToCount(checkpoint)

        // Crease glow along the spine, strongest mid-fold.
        spinePaint.shader = null
        spinePaint.color = 0xFFFFFFFF.toInt()
        spinePaint.alpha = (p * 70 * intensity).toInt().coerceIn(0, 110)
        val spineW = min(w, h) * 0.012f + 2f
        canvas.drawRect(w / 2f - spineW, 0f, w / 2f + spineW, h, spinePaint)
    }
}
