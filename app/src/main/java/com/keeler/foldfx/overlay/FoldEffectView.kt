package com.keeler.foldfx.overlay

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.view.View
import com.keeler.foldfx.overlay.effects.FoldEffect
import com.keeler.foldfx.capture.SnapshotFrames
import com.keeler.foldfx.optics.FoldSurface
import kotlin.math.abs

/** Full-screen overlay view that delegates all drawing to the active [FoldEffect]. */
class FoldEffectView(context: Context) : View(context) {

    var effect: FoldEffect? = null
    var intensity: Float = 1f
    var surface = FoldSurface.INNER
    var movingLeft = true
    var displayRotation = 0
    var hingeAngle = 180f
        set(value) {
            if (abs(value - field) > 0.001f) {
                field = value
                invalidate()
            }
        }
    private var renderer: StationaryRenderer? = null
    private var projectionUnavailable = false
    val hasProjection: Boolean get() = renderer != null
    var snapshotRotation: Int? = null
        private set

    fun setSnapshot(frame: SnapshotFrames.Frame?) {
        if (frame == null || Build.VERSION.SDK_INT < 33) {
            projectionUnavailable = false
            isClickable = false
            snapshotRotation = null
            if (renderer != null) { renderer = null; invalidate() }
            return
        }
        if (projectionUnavailable) return
        runCatching {
            val next = renderer ?: StationaryRenderer().also { renderer = it }
            next.bind(frame)
            isClickable = true
            snapshotRotation = frame.rotation
            invalidate()
        }.onFailure {
            projectionUnavailable = true
            isClickable = false
            renderer = null
            snapshotRotation = null
            Log.w("FoldFXTrace", "Projection unavailable; using live blur", it)
        }
    }

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
        if (Build.VERSION.SDK_INT >= 33 && renderer != null) {
            renderer!!.draw(canvas, width, height, hingeAngle, progress, intensity, surface, movingLeft, displayRotation)
            return
        }
        effect?.render(canvas, progress, width, height, intensity)
    }

    companion object {
        // Must stay below the manager's SETTLE_EPSILON (0.001f): the final
        // eased snap has to clear this threshold, or the last frame never
        // redraws and the effect freezes one frame early.
        private const val PROGRESS_EPSILON = 0.0005f
    }
}
