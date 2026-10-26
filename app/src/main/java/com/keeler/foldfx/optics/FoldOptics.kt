package com.keeler.foldfx.optics

import kotlin.math.cos
import kotlin.math.sin

/** A fixed image plane viewed through the moving half of a book fold. */
object FoldOptics {
    fun radians(angle: Float, surface: FoldSurface): Float {
        val tilt = if (surface == FoldSurface.INNER) 180f - angle else angle
        return Math.toRadians(tilt.coerceIn(0f, 85f).toDouble()).toFloat()
    }

    /** No projection on the stationary half; the crease is invariant at every angle. */
    fun sampleX(x: Float, width: Float, angle: Float, movingLeft: Boolean): Float {
        val hinge = width / 2f
        val side = if (movingLeft) -1f else 1f
        val distance = (x - hinge) * side
        if (distance <= 0f) return x
        val tilt = radians(angle, FoldSurface.INNER)
        val cameraDistance = width * 2.5f
        val perspective = cameraDistance / (cameraDistance - distance * sin(tilt))
        return hinge + side * distance * cos(tilt) * perspective
    }

    fun edgeWeight(distanceFromHinge: Float): Float {
        val d = distanceFromHinge.coerceIn(0f, 1f)
        return d * d * d
    }
}
