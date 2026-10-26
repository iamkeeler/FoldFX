package com.keeler.foldfx.overlay

import android.content.Context
import android.content.pm.ApplicationInfo
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
 * SurfaceFlinger recompute; the cheap GPU-local diffusion veil carries the fine
 * motion between pushes.
 */
class FoldOverlayManager(private val appContext: Context) {

    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val traceEnabled = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private data class AttachedOverlay(
        val windowManager: WindowManager,
        val view: FoldEffectView,
        val params: WindowManager.LayoutParams,
        val maxRadiusPx: Int,
        var lastRadius: Int = -1,
    )

    /** displayId -> overlay */
    private val overlays = mutableMapOf<Int, AttachedOverlay>()
    // Window contexts track configuration changes during the cover/inner handoff.
    // Reuse them until the display disappears; creating one per sensor event is expensive.
    private val windowContexts = mutableMapOf<Int, Context>()
    private val attachRetries = mutableMapOf<Int, Runnable>()

    var activeEffect: FoldEffect = EffectCatalog.all.first()
        private set
    var intensity: Float = 1f
        set(value) {
            val bounded = if (value.isFinite()) value.coerceIn(0.5f, 1.5f) else 1f
            if (bounded == field) return
            field = bounded
            overlays.values.forEach { it.view.intensity = bounded; it.view.invalidate() }
            if (running) scheduleFrame()
        }

    private var blurSupported = false
    private var running = false

    /** Latest target from the sensor; eased toward [renderedProgress] on vsync. */
    private var targetProgress = 0f
    private var renderedProgress = 0f
    private var lastFrameNanos = 0L
    private var frameCallbackScheduled = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            reconcileDisplay(displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            detach(displayId)
            cancelAttachRetry(displayId)
            windowContexts.remove(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            // A display can power on/off with no hinge motion (no sensor
            // events), e.g. pressing power while held half-open: keep overlay
            // membership in sync with the actual display state.
            reconcileDisplay(displayId)
        }
    }

    private fun reconcileDisplay(displayId: Int) {
        if (!running) return
        val display = displayManager.getDisplay(displayId) ?: return
        if (traceEnabled) Log.d(TRACE_TAG, "display=$displayId state=${display.state} target=$targetProgress attached=${overlays.containsKey(displayId)}")
        if (display.state == Display.STATE_ON && targetProgress > ATTACH_THRESHOLD) {
            maybeAttach(display)
        } else {
            cancelAttachRetry(displayId)
            if (display.state != Display.STATE_ON) detach(displayId)
        }
    }

    private val blurListener = Consumer<Boolean> { enabled ->
        if (running && blurSupported != enabled) {
            blurSupported = enabled
            if (traceEnabled) Log.d(TRACE_TAG, "blurEnabled=$enabled")
            // Also refresh a stationary half-open device, whose frame loop is parked.
            scheduleFrame()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCallbackScheduled = false
            val dt = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            }
            lastFrameNanos = frameTimeNanos

            // Frame-rate-independent easing: defocus catches the hinge quickly;
            // focus returns with a softer landing on 60/90/120 Hz alike.
            val tau = if (targetProgress > renderedProgress) DEFOCUS_TAU_SEC else FOCUS_TAU_SEC
            val alpha = 1f - exp(-dt / tau)
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

            if (renderedProgress != targetProgress) {
                scheduleFrame()
            } else {
                lastFrameNanos = 0L // parked: zero cost at rest
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        targetProgress = 0f
        renderedProgress = 0f
        lastFrameNanos = 0L
        displayManager.registerDisplayListener(displayListener, mainHandler)
        blurSupported = windowManager().isCrossWindowBlurEnabled
        if (traceEnabled) Log.d(TRACE_TAG, "overlay manager started blurEnabled=$blurSupported")
        windowManager().addCrossWindowBlurEnabledListener(appContext.mainExecutor, blurListener)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCallback)
        frameCallbackScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        attachRetries.clear()
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { windowManager().removeCrossWindowBlurEnabledListener(blurListener) }
        for (id in overlays.keys.toList()) detach(id)
        windowContexts.clear()
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
     * satisfy either condition, leaving a faint overlay stuck. The eased value
     * gives a smooth fade-out; release detaches once it settles below 0.012.
     */
    fun setTargetProgress(progress: Float) {
        if (!running) return
        targetProgress = if (progress.isFinite() && progress > ATTACH_THRESHOLD) {
            progress.coerceAtMost(1f)
        } else 0f
        if (targetProgress == 0f) {
            for (id in attachRetries.keys.toList()) cancelAttachRetry(id)
        }
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

    /** Fine 2px steps preserve the gradual onset without an IPC for every pixel. */
    private fun quantizeRadius(raw: Int) = raw - (raw % BLUR_QUANTUM_PX)

    private fun blurRadius(maxRadiusPx: Int): Int = if (blurSupported) {
        quantizeRadius((renderedProgress * maxRadiusPx * intensity).toInt().coerceAtMost(MAX_BLUR_PX))
    } else 0

    /** Pushes eased progress to views and the quantized blur radius. */
    private fun pushToOverlays() {
        for ((id, overlay) in overlays) {
            // effect/intensity are set at attach and on settings change;
            // only progress is pushed per frame (and only invalidates on
            // real change, see FoldEffectView).
            overlay.view.progress = renderedProgress
            val quantized = blurRadius(overlay.maxRadiusPx)
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

    private fun maybeAttach(display: Display, retryAttempt: Int = 0) {
        if (!running) return
        if (display.state != Display.STATE_ON) return // may have changed during the settle delay
        if (overlays.containsKey(display.displayId)) return
        if (targetProgress <= ATTACH_THRESHOLD) return
        try {
            val windowContext = windowContexts.getOrPut(display.displayId) {
                appContext.createWindowContext(
                    display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null,
                )
            }
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val view = FoldEffectView(windowContext).apply {
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // Draw into the cutout area: otherwise the punch-hole camera
                // leaves an unblurred notch in the middle of the effect.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // The diffusion reaches the edges, including the system-bar areas.
                setFitInsetsTypes(0)
                title = "FoldFX diffusion"
            }
            // Quantized to match pushToOverlays, so the first eased push
            // after attach doesn't fire a redundant updateViewLayout.
            val maxRadiusPx = (BLUR_RADIUS_DP * windowContext.resources.displayMetrics.density).toInt()
            val initialRadius = blurRadius(maxRadiusPx)
            params.setBlurBehindRadius(initialRadius)
            wm.addView(view, params)
            overlays[display.displayId] = AttachedOverlay(wm, view, params, maxRadiusPx, initialRadius)
            if (traceEnabled) Log.d(TRACE_TAG, "attached display=${display.displayId} target=$targetProgress radius=$initialRadius attempt=$retryAttempt")
            cancelAttachRetry(display.displayId)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach overlay to display ${display.displayId}", t)
            // A failed attach must not depend on another hinge reading to recover:
            // on-change sensors may already have reached their final posture.
            if (retryAttempt < ATTACH_RETRY_DELAYS_MS.size && !attachRetries.containsKey(display.displayId)) {
                val displayId = display.displayId
                val retry = Runnable {
                    attachRetries.remove(displayId)
                    if (running && targetProgress > ATTACH_THRESHOLD) {
                        displayManager.getDisplay(displayId)?.let { maybeAttach(it, retryAttempt + 1) }
                    }
                }
                attachRetries[displayId] = retry
                mainHandler.postDelayed(retry, ATTACH_RETRY_DELAYS_MS[retryAttempt])
            }
        }
    }

    private fun cancelAttachRetry(displayId: Int) {
        attachRetries.remove(displayId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun detach(displayId: Int) {
        val overlay = overlays.remove(displayId) ?: return
        if (traceEnabled) Log.d(TRACE_TAG, "detached display=$displayId target=$targetProgress")
        runCatching { overlay.windowManager.removeView(overlay.view) }
    }

    companion object {
        private const val TAG = "FoldOverlayManager"
        private const val TRACE_TAG = "FoldFXTrace"
        private const val ATTACH_THRESHOLD = 0.03f
        private const val DETACH_THRESHOLD = 0.012f
        private const val BLUR_QUANTUM_PX = 2
        private const val BLUR_RADIUS_DP = 36
        private const val MAX_BLUR_PX = 120
        private const val DEFOCUS_TAU_SEC = 0.045f
        private const val FOCUS_TAU_SEC = 0.07f
        private const val SETTLE_EPSILON = 0.001f
        private val ATTACH_RETRY_DELAYS_MS = longArrayOf(32L, 96L, 192L)
    }
}
