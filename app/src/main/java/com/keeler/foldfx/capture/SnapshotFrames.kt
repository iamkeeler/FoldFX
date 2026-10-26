package com.keeler.foldfx.capture

import android.graphics.Bitmap
import android.os.SystemClock
import com.keeler.foldfx.optics.FoldSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** RAM only: never serialized, backed up, or transmitted. Accessed on the main thread. */
object SnapshotFrames {
    data class Frame(val bitmap: Bitmap, val rotation: Int, val capturedAt: Long)

    private val frames = mutableMapOf<FoldSurface, Frame>()
    private val _available = MutableStateFlow<Set<FoldSurface>>(emptySet())
    val available: StateFlow<Set<FoldSurface>> = _available
    var onInvalidated: (() -> Unit)? = null
    var generation = 0
        private set

    fun get(surface: FoldSurface, rotation: Int): Frame? = frames[surface]?.takeIf {
        it.rotation == rotation && !it.bitmap.isRecycled &&
            SystemClock.uptimeMillis() - it.capturedAt <= 300_000L
    }

    fun publish(surface: FoldSurface, bitmap: Bitmap, rotation: Int) {
        frames[surface] = Frame(bitmap, rotation, SystemClock.uptimeMillis())
        _available.value = frames.keys.toSet()
    }

    fun clear() {
        // A renderer may still hold the old frame for this fold. Let reference ownership
        // release its buffer rather than recycling a bitmap still queued on the GPU.
        frames.clear()
        _available.value = emptySet()
        generation++
        onInvalidated?.invoke()
    }

    fun remove(surface: FoldSurface) {
        frames.remove(surface)
        _available.value = frames.keys.toSet()
    }
}
