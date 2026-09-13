package com.keeler.foldfx.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.keeler.foldfx.overlay.effects.BookFoldEffect
import com.keeler.foldfx.overlay.effects.FadeEffect
import com.keeler.foldfx.overlay.effects.FoldEffect
import com.keeler.foldfx.overlay.effects.PageTurnEffect

/**
 * Owns the system-overlay windows ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY])
 * on every display that is currently on.
 *
 * The blur is done by the compositor: [WindowManager.LayoutParams.setBlurBehindRadius]
 * blurs whatever is behind our (transparent) window, driven by fold progress.
 * If the device reports cross-window blur unavailable (GPU limits, battery
 * saver, ...), we degrade to the effect's own scrim with no blur.
 */
class FoldOverlayManager(private val appContext: Context) {

    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class AttachedOverlay(
        val windowManager: WindowManager,
        val view: FoldEffectView,
        val params: WindowManager.LayoutParams,
        var lastRadius: Int = -1,
    )

    /** displayId -> overlay */
    private val overlays = mutableMapOf<Int, AttachedOverlay>()

    val effects: List<FoldEffect> =
        listOf(BookFoldEffect(), FadeEffect(), PageTurnEffect())

    var activeEffect: FoldEffect = effects[0]
    var intensity: Float = 1f

    /** Blur radius in px at full progress; the system may clamp it. */
    var maxBlurRadiusPx: Int = 120

    private var blurSupported: Boolean = true
    private var lastProgress: Float = 0f

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            displayManager.getDisplay(displayId)?.let { maybeAttach(it, lastProgress) }
        }

        override fun onDisplayRemoved(displayId: Int) = detach(displayId)
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private val blurListener = { enabled: Boolean -> blurSupported = enabled }

    fun start() {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        for (display in displayManager.displays) {
            if (display.state == Display.STATE_ON) maybeAttach(display, lastProgress)
        }
        windowManager().addCrossWindowBlurEnabledListener(blurListener)
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { windowManager().removeCrossWindowBlurEnabledListener(blurListener) }
        for (id in overlays.keys.toList()) detach(id)
    }

    fun setEffectId(id: String) {
        effects.firstOrNull { it.id == id }?.let { activeEffect = it }
        overlays.values.forEach { it.view.effect = activeEffect }
    }

    /**
     * progress: 0 = settled (closed or flat), 1 = mid-fold.
     * The overlay only exists while progress is above the threshold, so when
     * the device is at rest we cost the compositor nothing.
     */
    fun setProgress(progress: Float) {
        lastProgress = progress
        val show = progress > PROGRESS_THRESHOLD
        if (show) {
            for (display in displayManager.displays) {
                if (display.state == Display.STATE_ON) maybeAttach(display, progress)
            }
        }
        for (id in overlays.keys.toList()) {
            val overlay = overlays[id] ?: continue
            overlay.view.effect = activeEffect
            overlay.view.intensity = intensity
            overlay.view.progress = progress
            val radius =
                if (blurSupported) (progress * maxBlurRadiusPx * intensity).toInt() else 0
            if (overlay.lastRadius != radius) {
                overlay.lastRadius = radius
                overlay.params.setBlurBehindRadius(radius)
                overlay.windowManager.updateViewLayout(overlay.view, overlay.params)
            }
            if (!show) detach(id)
        }
    }

    private fun windowManager(): WindowManager =
        appContext.getSystemService(WindowManager::class.java)

    private fun maybeAttach(display: Display, progress: Float) {
        if (overlays.containsKey(display.displayId)) return
        if (progress <= PROGRESS_THRESHOLD) return
        try {
            val displayContext = appContext.createDisplayContext(display)
            val wm = displayContext.getSystemService(WindowManager::class.java)
            val view = FoldEffectView(displayContext).apply {
                effect = activeEffect
                intensity = this@FoldOverlayManager.intensity
                this.progress = progress
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            val initialRadius =
                if (blurSupported) (progress * maxBlurRadiusPx * intensity).toInt() else 0
            if (blurSupported) {
                params.setBlurBehindRadius(initialRadius)
            }
            wm.addView(view, params)
            overlays[display.displayId] = AttachedOverlay(wm, view, params, initialRadius)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach overlay to display ${display.displayId}", t)
        }
    }

    private fun detach(displayId: Int) {
        val overlay = overlays.remove(displayId) ?: return
        runCatching { overlay.windowManager.removeView(overlay.view) }
    }

    companion object {
        private const val TAG = "FoldOverlayManager"
        private const val PROGRESS_THRESHOLD = 0.02f
    }
}
