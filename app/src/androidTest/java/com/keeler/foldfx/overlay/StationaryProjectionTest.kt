package com.keeler.foldfx.overlay

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keeler.foldfx.capture.SnapshotFrames
import com.keeler.foldfx.optics.FoldSurface
import com.keeler.foldfx.service.FoldEffectService
import com.keeler.foldfx.ui.MainActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a generated page, never a private user screen, to check the GPU projection. */
@RunWith(AndroidJUnit4::class)
class StationaryProjectionTest {
    @Test fun stationaryHalfRemainsPinnedWhileMovingHalfProjectsAndDiffuses() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        assertFalse("Unlock the phone and open its inner display flat", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        assertEquals("Open the inner display flat for this test", FoldSurface.INNER,
            FoldSurface.fromSize(display.mode.physicalWidth, display.mode.physicalHeight))
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var manager: FoldOverlayManager? = null
        try {
            ins.runOnMainSync { FoldEffectService.stop(context) }
            ins.waitForIdleSync()
            SystemClock.sleep(300) // Let the accessibility window-change event invalidate old pages first.
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val page = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(page)
            val paint = Paint()
            canvas.drawColor(Color.WHITE)
            for (x in 0 until size.x step 24) {
                paint.color = if ((x / 24) % 2 == 0) Color.rgb(35, 90, 210) else Color.rgb(240, 170, 40)
                canvas.drawRect(x.toFloat(), 0f, (x + 24).toFloat(), size.y.toFloat(), paint)
            }
            paint.color = Color.rgb(235, 35, 70)
            for (y in 250 until size.y - 200 step 220) {
                canvas.drawCircle(size.x * 0.22f, y.toFloat(), 45f, paint)
                canvas.drawCircle(size.x * 0.78f, y.toFloat(), 45f, paint)
            }
            ins.runOnMainSync {
                manager = FoldOverlayManager(context).also { it.start() }
                SnapshotFrames.publish(FoldSurface.INNER, page, display.rotation)
                manager!!.setHingeAngle(135f)
            }
            fun capture(name: String): Bitmap {
                SystemClock.sleep(650)
                ins.waitForIdleSync()
                ins.runOnMainSync {
                    val view = WindowInspector.getGlobalWindowViews().filterIsInstance<FoldEffectView>().first()
                    assertTrue("GPU shader must compile and bind the captured page", view.hasProjection)
                    assertEquals("Uniform blur would erase spatial focus", 0,
                        (view.layoutParams as WindowManager.LayoutParams).blurBehindRadius)
                    assertEquals("A frozen page must receive touches to avoid Android's pass-through opacity cap", 0,
                        (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                }
                val image = checkNotNull(ins.uiAutomation.takeScreenshot())
                val file = File(context.getExternalFilesDir(null), "diffusion-qa/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return image
            }
            val a = capture("projected-135")
            ins.runOnMainSync { manager!!.setHingeAngle(100f) }
            val b = capture("projected-100")
            // A solid stationary stripe must replace the live page, not ghost over it.
            val stripeX = size.x * 85 / 100
            val expectedStripe = if ((stripeX / 24) % 2 == 0) Color.rgb(35, 90, 210) else Color.rgb(240, 170, 40)
            val actualStripe = a.getPixel(stripeX, size.y / 2)
            assertTrue("The frozen page must be opaque", kotlin.math.abs(Color.red(actualStripe) - Color.red(expectedStripe)) < 4 &&
                kotlin.math.abs(Color.green(actualStripe) - Color.green(expectedStripe)) < 4 &&
                kotlin.math.abs(Color.blue(actualStripe) - Color.blue(expectedStripe)) < 4)
            var stationaryDifference = 0L
            var movingDifference = 0L
            var samples = 0
            for (y in size.y / 4 until size.y * 3 / 4 step 8) {
                for (x in size.x * 3 / 4 until size.x * 9 / 10 step 8) {
                    fun difference(left: Int, right: Int): Int =
                        kotlin.math.abs(Color.red(left) - Color.red(right)) +
                        kotlin.math.abs(Color.green(left) - Color.green(right)) +
                        kotlin.math.abs(Color.blue(left) - Color.blue(right))
                    stationaryDifference += difference(a.getPixel(x, y), b.getPixel(x, y))
                    val movingX = size.x - x
                    movingDifference += difference(a.getPixel(movingX, y), b.getPixel(movingX, y))
                    samples++
                }
            }
            assertTrue("Stationary icons must not drift or blur", stationaryDifference.toDouble() / samples < 2.0)
            assertTrue("The folding pane must change projection and diffusion", movingDifference.toDouble() / samples > 10.0)
            ins.runOnMainSync { manager!!.setHingeAngle(180f) }
            SystemClock.sleep(650)
            ins.runOnMainSync {
                assertTrue(WindowInspector.getGlobalWindowViews().none { it is FoldEffectView })
            }
            a.recycle()
            b.recycle()
        } finally {
            ins.runOnMainSync { manager?.stop(); activity.finish() }
        }
    }
}
