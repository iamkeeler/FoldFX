package com.keeler.foldfx.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.keeler.foldfx.overlay.effects.FoldEffect

/** Full-screen overlay view that delegates all drawing to the active [FoldEffect]. */
class FoldEffectView(context: Context) : View(context) {

    var effect: FoldEffect? = null
    var intensity: Float = 1f

    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Fully transparent: the window's blur-behind radius (set on the
        // LayoutParams) does the blurring; we only draw accents on top.
        setBackgroundColor(0x00000000)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        effect?.render(canvas, progress, width, height, intensity)
    }
}
