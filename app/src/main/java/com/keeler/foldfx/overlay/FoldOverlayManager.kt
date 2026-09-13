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
import com.keeler.foldfx.overlay.effects.BookFoldEffect
import com.keeler.foldfx.overlay.effects.FadeEffect
import com.keeler.foldfx.overlay.effects.FoldEffect
import com.keeler.foldfx.overlay.effects.PageTurnEffect
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

    val effects: List<FoldEffect> =
        listOf(BookFoldEffect(), FadeEffect(), PageTurnEffect())

    var activeEffect: FoldEffect = effects[0]
    var intensity: Float = 1f

    /** Blur radius in px at full progress; density-scaled, the system may clamp it. */
    var maxBlurRadiusPx: Int =
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

            if (renderedProgress != targetProgress || targetProgress > DETACH_THRESHOLD ||
                overlays.isNotEmpty()
            ) {
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
        effects.firstOrNull { it.id == id }?.let { activeEffect = it }
        overlays.values.forEach { it.view.effect = activeEffect }
    }

    /**
     * progress: 0 = settled (closed or flat), 1 = mid-fold. Cheap: records the
     * target and manages overlay lifetime. Actual rendering happens on the
     * Choreographer loop. Attach at 0.03; release happens once the *eased*
     * progress settles below 0.012, so fast folds fade out instead of popping.
     */
    fun setTargetProgress(progress: Float) {
        targetProgress = progress
        if (progress > ATTACH_THRESHOLD) {
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

    /** Pushes eased progress to views and the quantized blur radius. */
    private fun pushToOverlays() {
        for (id in overlays.keys.toList()) {
            val overlay = overlays[id] ?: continue
            overlay.view.effect = activeEffect
            overlay.view.intensity = intensity
            overlay.view.progress = renderedProgress
            val radius = if (blurSupported) {
                (renderedProgress * maxBlurRadiusPx * intensity).toInt()
            } else {
                0
            }
            // Quantize: sub-6px blur steps are invisible, and each push is a
            // WindowManager IPC + SurfaceFlinger recompute.
            val quantized = radius - (radius % BLUR_QUANTUM_PX)
            if (overlay.lastRadius != quantized) {
                overlay.lastRadius = quantized
                overlay.params.setBlurBehindRadius(quantized)
                overlay.windowManager.updateViewLayout(overlay.view, overlay.params)
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
            val initialRadius = if (blurSupported) {
                (renderedProgress * maxBlurRadiusPx * intensity).toInt()
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
