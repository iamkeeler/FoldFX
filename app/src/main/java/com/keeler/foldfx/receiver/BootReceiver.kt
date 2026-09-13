package com.keeler.foldfx.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.keeler.foldfx.prefs.Prefs
import com.keeler.foldfx.service.FoldEffectService

/** Restarts the hinge watcher after reboot if the user left FoldFX enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).enabled) FoldEffectService.start(context)
    }
}
