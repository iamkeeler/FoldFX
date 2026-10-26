package com.keeler.foldfx.service

/** Focus follows posture, so reversing direction retraces the same diffusion curve. */
internal object FoldProgress {
    /** Catch the first 5° sample on the cover; settle back to sharp near flat. */
    fun fromAngle(angle: Float?): Float {
        if (angle == null || !angle.isFinite()) return 0f
        fun smooth(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        return minOf(smooth(angle / 12f), smooth((178f - angle) / 88f))
    }
}
