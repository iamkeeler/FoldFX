package com.keeler.foldfx.overlay.effects

import android.graphics.Canvas

/**
 * A pluggable fold-transition visual.
 *
 * [progress] is 0 when the device is settled (fully closed or fully flat)
 * and ramps to 1 mid-fold. Render fast: this runs every frame while the
 * effect is active, so avoid allocations in [render].
 */
interface FoldEffect {
    val id: String
    val displayName: String
    fun render(canvas: Canvas, progress: Float, width: Int, height: Int, intensity: Float)

    companion object {
        /**
         * Progress coerced to [0, 1], or null when fully settled — the shared
         * early-out, so effects never draw invisible frames.
         */
        fun activeProgress(progress: Float): Float? =
            progress.coerceIn(0f, 1f).takeIf { it > 0f }

        /** Alpha scaled by progress and intensity, capped at [max]. */
        fun scaledAlpha(progress: Float, factor: Float, intensity: Float, max: Int): Int =
            (progress * factor * intensity).toInt().coerceIn(0, max)
    }
}
