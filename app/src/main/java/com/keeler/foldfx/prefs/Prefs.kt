package com.keeler.foldfx.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Tiny SharedPreferences wrapper for FoldFX settings. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("foldfx", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_ENABLED, v) }

    var effectId: String
        get() = sp.getString(KEY_EFFECT, EFFECT_BOOK_FOLD) ?: EFFECT_BOOK_FOLD
        set(v) = sp.edit { putString(KEY_EFFECT, v) }

    /** Visual intensity multiplier, 0.5x..1.5x. */
    var intensity: Float
        get() = sp.getFloat(KEY_INTENSITY, 1f)
        set(v) = sp.edit { putFloat(KEY_INTENSITY, v.coerceIn(0.5f, 1.5f)) }

    companion object {
        const val EFFECT_BOOK_FOLD = "book_fold"
        const val EFFECT_FADE = "fade"
        const val EFFECT_PAGE_TURN = "page_turn"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_EFFECT = "effect_id"
        private const val KEY_INTENSITY = "intensity"
    }
}
