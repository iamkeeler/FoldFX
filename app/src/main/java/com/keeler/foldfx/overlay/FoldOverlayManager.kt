package com.keeler.foldfx.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import com.keeler.foldfx.overlay.effects.EffectCatalog
import com.keeler.foldfx.overlay.effects.FoldEffect
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Owns the system-overlay windows ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY])
 * on every display that is currently on.
 *
 * The blur is done by the compositor: [WindowManager.LayoutParams.setBlurBehindRadius]
 * blurs whatever is behind our (transparent) window, driven by fold progress.
 * If the device reports cross-window blur unavailable (GPU limits, battery
 * saver, ...), we degrade to the effect's own scrim with no blur.
 *
 * Render pipeline: the sensor writes [targetProgress] at sensor rate; a
 * [Choreographer] loop eases [renderedProgress] toward it with a time-based
 * exponential ease, so 5° sensor steps become buttery motion on any refresh
 * rate — and the loop parks itself at rest, costing nothing when settled.
 * Blur radius pushes are quantized because each one is a WindowManager IPC +
 * SurfaceFlinger recompute; the cheap GPU-local scrim/sweep carries the fine
 * motion between pushes.
 */
class FoldOverlayManager(private val appContext: Context) {

    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    private data class AttachedOverlay(
        val windowManager: WindowManager,
        val view: FoldEffectView,
        val params: WindowManager.LayoutParams,
        var lastRadius: Int = -1,
    )

    /** displayId -> overlay */
    private val overlays = mutableMapOf<Int, AttachedOverlay>()

    var activeEffect: FoldEffect = EffectCatalog.all.first()
        private set
    var intensity: Float = 1f
        set(value) {
            if (value == field) return
            field = value
            // Same invalidate reasoning as setEffectId: converged views
            // don't redraw on their own.
            overlays.values.forEach { it.view.invalidate() }
        }

    /** Blur radius in px at full progress; density-scaled, the system may clamp it. */
    val maxBlurRadiusPx: Int =
        (BLUR_RADIUS_DP * appContext.resources.displayMetrics.density).toInt()

    private var blurSupported: Boolean = true

    /** Latest target from the sensor; eased toward [renderedProgress] on vsync. */
    private var targetProgress = 0f
    private var renderedProgress = 0f
    private var lastFrameNanos = 0L
    private var frameCallbackScheduled = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // Let the newly-on display draw its first frame before we blur
            // over it; otherwise the effect opens over black.
            mainHandler.postDelayed({
                displayManager.getDisplay(displayId)?.let { maybeAttach(it) }
            }, DISPLAY_SETTLE_DELAY_MS)
        }

        override fun onDisplayRemoved(displayId: Int) = detach(displayId)

        override fun onDisplayChanged(displayId: Int) {
            // A display can power on/off with no hinge motion (no sensor
            // events), e.g. pressing power while held half-open: keep overlay
            // membership in sync with the actual display state.
            val display = displayManager.getDisplay(displayId) ?: return
            if (display.state == Display.STATE_ON) {
                if (targetProgress > ATTACH_THRESHOLD) maybeAttach(display)
            } else {
                detach(displayId)
            }
        }
    }

    private val blurListener = Consumer<Boolean> { enabled -> blurSupported = enabled }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCallbackScheduled = false
            val dt = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            }
            lastFrameNanos = frameTimeNanos

            // Time-based exponential ease: frame-rate independent, correct on
            // 60/90/120 Hz alike. TAU ~90ms tracks a fast fold with no lag feel.
            val alpha = 1f - exp(-dt / EASE_TAU_SEC)
            renderedProgress += (targetProgress - renderedProgress) * alpha
            if (abs(targetProgress - renderedProgress) < SETTLE_EPSILON) {
                renderedProgress = targetProgress
            }
            pushToOverlays()

            // Release only once the *eased* value settles: detaching on the
            // raw sensor target would pop the effect off mid-fade on fast folds.
            if (targetProgress <= DETACH_THRESHOLD && renderedProgress <= DETACH_THRESHOLD) {
                for (id in overlays.keys.toList()) detach(id)
                renderedProgress = 0f
                targetProgress = 0f
            }

            if (renderedProgress != targetProgress || overlays.isNotEmpty()) {
                scheduleFrame()
            } else {
                lastFrameNanos = 0L // parked: zero cost at rest
            }
        }
    }

    fun start() {
        targetProgress = 0f
        renderedProgress = 0f
        lastFrameNanos = 0L
        displayManager.registerDisplayListener(displayListener, mainHandler)
        windowManager().addCrossWindowBlurEnabledListener(blurListener)
    }

    fun stop() {
        choreographer.removeFrameCallback(frameCallback)
        frameCallbackScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { windowManager().removeCrossWindowBlurEnabledListener(blurListener) }
        for (id in overlays.keys.toList()) detach(id)
        targetProgress = 0f
        renderedProgress = 0f
    }

    fun setEffectId(id: String) {
        EffectCatalog.all.firstOrNull { it.id == id }?.let {
            if (it == activeEffect) return
            activeEffect = it
            // Invalidate: attached views may be converged (e.g. phone held
            // half-open) and wouldn't otherwise redraw on a settings change.
            overlays.values.forEach { o -> o.view.effect = it; o.view.invalidate() }
        }
    }

    /**
     * progress: 0 = settled (closed or flat), 1 = mid-fold. Cheap: records the
     * target and manages overlay lifetime. Actual rendering happens on the
     * Choreographer loop.
     *
     * Sub-attach targets snap to zero: without the snap, a target parked
     * between the detach (0.012) and attach (0.03) thresholds could never
     * satisfy either condition — spinning the Choreographer forever and/or
     * leaving a faint overlay stuck. The eased value still gives the smooth
     * fade-out; release detaches once it settles below 0.012.
     */
    fun setTargetProgress(progress: Float) {
        targetProgress = if (progress > ATTACH_THRESHOLD) progress else 0f
        if (targetProgress > ATTACH_THRESHOLD) {
            for (display in displayManager.displays) {
                if (display.state == Display.STATE_ON) maybeAttach(display)
            }
        }
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!frameCallbackScheduled) {
            frameCallbackScheduled = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    /** Sub-6px blur steps are invisible; each push is a WindowManager IPC. */
    private fun quantizeRadius(raw: Int) = raw - (raw % BLUR_QUANTUM_PX)

    /** Pushes eased progress to views and the quantized blur radius. */
    private fun pushToOverlays() {
        for ((id, overlay) in overlays) {
            // effect/intensity are set at attach and on settings change;
            // only progress is pushed per frame (and only invalidates on
            // real change, see FoldEffectView).
            overlay.view.progress = renderedProgress
            val radius = if (blurSupported) {
                (renderedProgress * maxBlurRadiusPx * intensity).toInt()
            } else {
                0
            }
            val quantized = quantizeRadius(radius)
            if (overlay.lastRadius != quantized) {
                overlay.lastRadius = quantized
                overlay.params.setBlurBehindRadius(quantized)
                // The overlay permission can be revoked mid-fold, tearing our
                // windows down out from under us: never let the frame
                // callback die on it.
                val ok = runCatching {
                    overlay.windowManager.updateViewLayout(overlay.view, overlay.params)
                }.isSuccess
                if (!ok) {
                    detach(id)
                    break // map mutated; remaining overlays resume next frame
                }
            }
        }
    }

    private fun windowManager(): WindowManager =
        appContext.getSystemService(WindowManager::class.java)

    private fun maybeAttach(display: Display) {
        if (display.state != Display.STATE_ON) return // may have changed during the settle delay
        if (overlays.containsKey(display.displayId)) return
        if (targetProgress <= ATTACH_THRESHOLD) return
        try {
            val displayContext = appContext.createDisplayContext(display)
            val wm = displayContext.getSystemService(WindowManager::class.java)
            val view = FoldEffectView(displayContext).apply {
                effect = activeEffect
                intensity = this@FoldOverlayManager.intensity
                progress = renderedProgress
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // Draw into the cutout area: otherwise the punch-hole camera
                // leaves an unblurred notch in the middle of the effect.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Quantized to match pushToOverlays, so the first eased push
            // after attach doesn't fire a redundant updateViewLayout.
            val initialRadius = if (blurSupported) {
                quantizeRadius((renderedProgress * maxBlurRadiusPx * intensity).toInt())
            } else {
                0
            }
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
        private const val ATTACH_THRESHOLD = 0.03f
        private const val DETACH_THRESHOLD = 0.012f
        private const val BLUR_QUANTUM_PX = 6
        private const val BLUR_RADIUS_DP = 48
        private const val EASE_TAU_SEC = 0.09f
        private const val SETTLE_EPSILON = 0.001f
        private const val DISPLAY_SETTLE_DELAY_MS = 120L
    }
}
