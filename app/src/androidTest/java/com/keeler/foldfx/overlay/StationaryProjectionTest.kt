package com.keeler.foldfx.overlay

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
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

/** Uses a generated page, never a private user screen, to check pinned GPU diffusion. */
@RunWith(AndroidJUnit4::class)
class StationaryProjectionTest {
    /** Exercises the actual GPU shader without a lit/unlocked display or private screenshot. */
    @Test fun offscreenGpuKeepsLandmarksPinnedAtEveryAngle() {
        val width = 1000
        val height = 1040
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(page)
        val ink = Paint()
        for (x in 0 until width step 24) {
            ink.color = if (x / 24 % 2 == 0) Color.BLUE else Color.WHITE
            sourceCanvas.drawRect(x.toFloat(), 0f, (x + 24).toFloat(), height.toFloat(), ink)
        }
        ink.color = Color.rgb(235, 35, 70)
        for (x in listOf(220f, 780f)) sourceCanvas.drawCircle(x, 520f, 35f, ink)
        val frame = SnapshotFrames.Frame(page, 0, SystemClock.uptimeMillis())
        fun render(angle: Float, movingLeft: Boolean): Bitmap {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val hardware = HardwareRenderer()
            val node = RenderNode("FoldFX pinned diffusion")
            try {
                node.setPosition(0, 0, width, height)
                val canvas = node.beginRecording()
                StationaryRenderer().apply { bind(frame) }.draw(canvas, width, height, angle, 1f,
                    1f, FoldSurface.INNER, movingLeft, 0)
                node.endRecording()
                hardware.setSurface(reader.surface)
                hardware.setContentRoot(node)
                hardware.setOpaque(true)
                hardware.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val image = checkNotNull(reader.acquireNextImage())
                return image.use {
                    val buffer = checkNotNull(it.hardwareBuffer)
                    try {
                        checkNotNull(Bitmap.wrapHardwareBuffer(buffer, null)).copy(Bitmap.Config.ARGB_8888, false)
                    } finally { buffer.close() }
                }
            } finally {
                hardware.destroy()
                reader.close()
            }
        }
        for (left in listOf(true, false)) {
            for (angle in listOf(180f, 150f, 120f, 100f)) {
                val image = render(angle, left)
                try {
                    for (centerX in listOf(220, 780)) {
                        var mass = 0.0
                        var wx = 0.0
                        var wy = 0.0
                        for (y in 450..590) for (x in centerX - 70..centerX + 70) {
                            val c = image.getPixel(x, y)
                            val m = (Color.red(c) - maxOf(Color.green(c), Color.blue(c)) * 1.5).coerceAtLeast(0.0)
                            mass += m; wx += x * m; wy += y * m
                        }
                        assertTrue("Icon disappeared at angle=$angle movingLeft=$left", mass > 5000.0)
                        assertEquals("Icon slid horizontally at angle=$angle movingLeft=$left", centerX.toDouble(), wx / mass, 4.0)
                        assertEquals("Icon slid vertically at angle=$angle movingLeft=$left", 520.0, wy / mass, 4.0)
                    }
                    if (angle == 100f) {
                        var movingChange = 0L
                        var stationaryChange = 0L
                        var samples = 0
                        for (y in 200..400 step 8) for (x in 50..200 step 8) {
                            fun difference(xPixel: Int): Int {
                                val a = image.getPixel(xPixel, y)
                                val b = page.getPixel(xPixel, y)
                                return kotlin.math.abs(Color.red(a) - Color.red(b)) +
                                    kotlin.math.abs(Color.green(a) - Color.green(b)) +
                                    kotlin.math.abs(Color.blue(a) - Color.blue(b))
                            }
                            movingChange += difference(if (left) x else width - x)
                            stationaryChange += difference(if (left) width - x else x)
                            samples++
                        }
                        assertTrue("An angled page must visibly diffuse", movingChange.toDouble() / samples > 10.0)
                        assertTrue("The other half must retain sharp detail", stationaryChange.toDouble() / samples < 2.0)
                        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                            "diffusion-qa/pinned-${if (left) "left" else "right"}-100.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { image.recycle() }
            }
        }
        page.recycle()
    }

    @Test fun bothHalvesStayRegisteredWhileOnlyOpticalSpreadChanges() {
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
            // Both folding-side landmarks must stay at their original coordinates.
            // This catches perspective scaling/translation even if the other half is pinned.
            val landmarkX = size.x * 0.22f
            val landmarkY = (250 until size.y - 200 step 220).minBy { kotlin.math.abs(it - size.y / 2) }
            fun assertLandmarkPinned(image: Bitmap) {
                var mass = 0.0
                var weightedX = 0.0
                var weightedY = 0.0
                for (y in landmarkY - 90..landmarkY + 90) {
                    for (x in landmarkX.toInt() - 90..landmarkX.toInt() + 90) {
                        val c = image.getPixel(x, y)
                        val redMass = (Color.red(c) - maxOf(Color.green(c), Color.blue(c)) * 1.5).coerceAtLeast(0.0)
                        mass += redMass
                        weightedX += x * redMass
                        weightedY += y * redMass
                    }
                }
                assertTrue("The folding-side landmark must stay visible at its original position", mass > 5000.0)
                assertEquals("Diffusion must not slide icons horizontally", landmarkX.toDouble(), weightedX / mass, 4.0)
                assertEquals("Diffusion must not build icons in from off screen", landmarkY.toDouble(), weightedY / mass, 4.0)
            }
            assertLandmarkPinned(a)
            assertLandmarkPinned(b)
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
            assertTrue("The folding pane must change diffusion", movingDifference.toDouble() / samples > 10.0)
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
