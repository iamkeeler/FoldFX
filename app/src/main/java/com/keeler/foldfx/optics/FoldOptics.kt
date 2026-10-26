package com.keeler.foldfx.optics

import kotlin.math.sin

/** Angle changes optical spread while the renderer keeps page geometry fixed. */
object FoldOptics {
    fun diffusionStrength(angle: Float): Float {
        if (!angle.isFinite()) return 0f
        val tilt = 180f - angle.coerceIn(0f, 180f)
        return sin(Math.toRadians(tilt.coerceAtMost(85f).toDouble())).toFloat()
    }

    fun edgeWeight(distanceFromHinge: Float): Float {
        val d = distanceFromHinge.coerceIn(0f, 1f)
        return d * d * d
    }
}
