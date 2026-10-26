package com.keeler.foldfx.overlay

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keeler.foldfx.service.FoldEffectService
import com.keeler.foldfx.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverOnsetTest {
    @Test fun firstMotionStartsWithoutAnEasingDelayAndIdleNoiseReleases() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var manager: FoldOverlayManager? = null
        try {
            ins.runOnMainSync { FoldEffectService.stop(context) }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                manager = FoldOverlayManager(context).also { it.stationaryContent = false; it.start() }
                manager!!.setHingeAngle(0f)
                manager!!.setHingeAngle(5f)
                val overlay = WindowInspector.getGlobalWindowViews().filterIsInstance<FoldEffectView>().first()
                assertTrue("First visible frame needs blur, not a zero-radius easing start",
                    (overlay.layoutParams as WindowManager.LayoutParams).blurBehindRadius > 0)
            }
            SystemClock.sleep(700)
            ins.runOnMainSync {
                assertTrue("A closed sensor idling at 5 degrees must release its overlay",
                    WindowInspector.getGlobalWindowViews().none { it is FoldEffectView })
                manager!!.setHingeAngle(0f)
                manager!!.setHingeAngle(5f)
                manager!!.setHingeAngle(10f)
            }
            SystemClock.sleep(700)
            ins.runOnMainSync {
                assertTrue("Continued opening must cancel the noise-release timer",
                    WindowInspector.getGlobalWindowViews().any { it is FoldEffectView })
            }
        } finally {
            ins.runOnMainSync { manager?.stop(); activity.finish() }
        }
    }
}
