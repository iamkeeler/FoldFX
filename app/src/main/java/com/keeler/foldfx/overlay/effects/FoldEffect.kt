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
}
