package com.keeler.foldfx.service

/** Focus follows posture, so reversing direction retraces the same diffusion curve. */
internal object FoldProgress {
    /** Sharp at either settled end; fully diffused across the middle 60°..120°. */
    fun fromAngle(angle: Float?): Float {
        if (angle == null || !angle.isFinite()) return 0f
        val distanceFromRest = minOf(angle, 180f - angle)
        val t = ((distanceFromRest - 6f) / 54f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
