package com.keeler.foldfx.optics

/** Physical-panel identity survives logical display IDs being reassigned during a fold. */
enum class FoldSurface {
    INNER, COVER;

    companion object {
        fun fromSize(width: Int, height: Int): FoldSurface =
            if (minOf(width, height).toFloat() / maxOf(width, height).coerceAtLeast(1) >= 0.7f) INNER else COVER
    }
}
