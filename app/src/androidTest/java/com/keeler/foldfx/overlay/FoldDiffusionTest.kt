package com.keeler.foldfx.overlay

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keeler.foldfx.prefs.Prefs
import com.keeler.foldfx.service.FoldEffectService
import com.keeler.foldfx.ui.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real overlay windows and compositor blur on an unlocked, blur-capable foldable. */
@RunWith(AndroidJUnit4::class)
class FoldDiffusionTest {
    @Test fun pageDefocusesThenReturnsToFocusAndReleasesItsWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Grant FoldFX display-over-other-apps permission first", Settings.canDrawOverlays(context))
        assertFalse("Unlock the device before testing", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue("Enable window blur before testing", context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled)

        val wasEnabled = Prefs(context).enabled
        var manager: FoldOverlayManager? = null
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.runOnMainSync { FoldEffectService.stop(context) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                manager = FoldOverlayManager(context).also { it.start() }
            }
            fun settle(progress: Float) {
                instrumentation.runOnMainSync { manager!!.setTargetProgress(progress) }
                SystemClock.sleep(650)
                instrumentation.waitForIdleSync()
            }
            fun radius(): Int {
                var result = -1
                instrumentation.runOnMainSync {
                    val views = WindowInspector.getGlobalWindowViews().filterIsInstance<FoldEffectView>()
                    assertTrue("Expected an attached fold overlay", views.isNotEmpty())
                    for (view in views) {
                        val params = view.layoutParams as WindowManager.LayoutParams
                        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
                        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                        assertTrue(params.blurBehindRadius in 1..120)
                    }
                    result = (views.first().layoutParams as WindowManager.LayoutParams).blurBehindRadius
                }
                return result
            }
            fun capture(name: String) {
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val file = File(context.getExternalFilesDir(null), "diffusion-qa/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            settle(0f)
            capture("sharp")
            settle(0.2f)
            val earlyRadius = radius()
            settle(1f)
            val fullRadius = radius()
            assertTrue("Diffusion must deepen with progress", fullRadius > earlyRadius)
            capture("diffused")

            // Change intensity while the hinge and the frame loop are at rest.
            instrumentation.runOnMainSync { manager!!.intensity = 0.5f }
            SystemClock.sleep(150)
            val reducedRadius = radius()
            assertTrue("Intensity must refresh a stationary overlay", reducedRadius < fullRadius)
            instrumentation.runOnMainSync { manager!!.intensity = 1.5f }
            SystemClock.sleep(150)
            assertTrue(radius() > reducedRadius)

            settle(0.2f)
            assertTrue("Closing must restore focus", radius() < fullRadius)
            // Reverse before settling: no stuck window or residual blur at the end.
            instrumentation.runOnMainSync {
                manager!!.setTargetProgress(0.8f)
                manager!!.setTargetProgress(0f)
            }
            settle(0f)
            instrumentation.runOnMainSync {
                assertEquals(0, WindowInspector.getGlobalWindowViews().filterIsInstance<FoldEffectView>().size)
            }
            capture("refocused")
        } finally {
            instrumentation.runOnMainSync {
                manager?.stop()
                if (wasEnabled) FoldEffectService.start(context)
                activity.finish()
            }
        }
    }
}
