package com.keeler.foldfx.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.keeler.foldfx.prefs.Prefs
import com.keeler.foldfx.service.FoldEffectService

/** Restarts the hinge watcher after reboot if the user left FoldFX enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs(context).enabled) return
        // startForegroundService can be rejected for background starts on
        // some devices/versions; don't let the receiver die on it — the
        // user can still enable FoldFX manually from the app.
        runCatching { FoldEffectService.start(context) }
            .onFailure { Log.w(TAG, "Could not restart FoldFX after boot", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
