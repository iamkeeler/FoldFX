package com.keeler.foldfx.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.keeler.foldfx.overlay.effects.FoldEffect
import kotlin.math.abs

/** Full-screen overlay view that delegates all drawing to the active [FoldEffect]. */
class FoldEffectView(context: Context) : View(context) {

    var effect: FoldEffect? = null
    var intensity: Float = 1f

    var progress: Float = 0f
        set(value) {
            // The Choreographer loop pushes every vsync; only redraw on real
            // change so a phone held half-open doesn't repaint identical
            // frames at 120 Hz forever.
            if (abs(value - field) > PROGRESS_EPSILON) {
                field = value
                invalidate()
            }
        }

    init {
        // Fully transparent: the window's blur-behind radius (set on the
        // LayoutParams) does the blurring; we only draw accents on top.
        setBackgroundColor(0x00000000)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        effect?.render(canvas, progress, width, height, intensity)
    }

    companion object {
        // Must stay below the manager's SETTLE_EPSILON (0.001f): the final
        // eased snap has to clear this threshold, or the last frame never
        // redraws and the effect freezes one frame early.
        private const val PROGRESS_EPSILON = 0.0005f
    }
}
