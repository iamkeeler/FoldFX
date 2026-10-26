package com.keeler.foldfx.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.keeler.foldfx.optics.FoldSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Screenshot-only capability: no node inspection, gestures, or window-content retrieval. */
class ScreenCaptureService : AccessibilityService() {
    private var busy = false
    private var lastRequest = -1000L
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        connection = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // System bars and the lock screen can change during the handoff without
            // the user's underlying app changing. They must not discard its flat image.
            if (pkg == "com.android.systemui") return
            if (lastPackage != null && lastPackage != pkg) SnapshotFrames.clear()
            lastPackage = pkg
        }
    }

    override fun onInterrupt() { SnapshotFrames.clear() }

    override fun onDestroy() {
        if (connection === this) {
            connection = null
            _connected.value = false
            SnapshotFrames.clear()
        }
        super.onDestroy()
    }

    private fun capture(
        displayId: Int, surface: FoldSurface, rotation: Int,
        accept: () -> Boolean, finished: () -> Unit,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (busy || now - lastRequest < 400L ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        busy = true
        lastRequest = now
        val generation = SnapshotFrames.generation
        try {
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    busy = false
                    val buffer = result.hardwareBuffer
                    try {
                        if (generation == SnapshotFrames.generation && accept() &&
                            !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let {
                                if (FoldSurface.fromSize(it.width, it.height) == surface) {
                                    SnapshotFrames.publish(surface, it, rotation)
                                    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                                        Log.d("FoldFXTrace", "captured display=$displayId surface=$surface size=${it.width}x${it.height}")
                                    }
                                }
                            }
                        }
                    } finally {
                        buffer.close()
                        finished()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false
                    SnapshotFrames.remove(surface)
                    Log.d("FoldFXTrace", "capture unavailable display=$displayId code=$errorCode; using live blur")
                    finished()
                }
            })
        } catch (e: RuntimeException) {
            busy = false
            SnapshotFrames.remove(surface)
            Log.w("FoldFXTrace", "Screen capture unavailable; using live blur", e)
            finished()
        }
        return true
    }

    companion object {
        private var connection: ScreenCaptureService? = null
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        fun request(
            displayId: Int, surface: FoldSurface, rotation: Int,
            accept: () -> Boolean, finished: () -> Unit = {},
        ): Boolean = connection?.capture(displayId, surface, rotation, accept, finished) ?: false
    }
}
