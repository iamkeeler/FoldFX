package com.keeler.foldfx.overlay.effects

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * A glass "page" that lifts and rotates in 3D around the spine as the fold
 * deepens, like a book page caught mid-turn. Demonstrates that effects can
 * do full perspective transforms, not just fades.
 */
class PageTurnEffect : FoldEffect {

    override val id = "page_turn"
    override val displayName = "Page Turn"

    private val camera = Camera()
    private val matrix = Matrix()
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint()

    override fun render(canvas: Canvas, progress: Float, width: Int, height: Int, intensity: Float) {
        val p = FoldEffect.activeProgress(progress) ?: return

        val w = width.toFloat()
        val h = height.toFloat()

        shadowPaint.color = 0xFF000000.toInt()
        shadowPaint.alpha = FoldEffect.scaledAlpha(p, 110f, intensity, 170)
        canvas.drawRect(0f, 0f, w, h, shadowPaint)

        // Rotate a translucent page around the vertical spine.
        camera.save()
        camera.rotateY(p * 38f) // up to 38° of tilt at mid-fold
        camera.getMatrix(matrix)
        camera.restore()

        val cx = w / 2f
        val cy = h / 2f
        matrix.preTranslate(-cx, -cy)
        matrix.postTranslate(cx, cy)

        val checkpoint = canvas.save()
        canvas.concat(matrix)
        pagePaint.color = 0xFFFFFFFF.toInt()
        pagePaint.alpha = FoldEffect.scaledAlpha(p, 46f, intensity, 90)
        val inset = w * 0.06f
        // Float overload: no RectF allocation on the draw path.
        canvas.drawRoundRect(inset, h * 0.08f, w - inset, h * 0.92f, 32f, 32f, pagePaint)
        canvas.restoreToCount(checkpoint)
    }
}
