package com.keeler.foldfx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.keeler.foldfx.R
import com.keeler.foldfx.overlay.FoldOverlayManager
import com.keeler.foldfx.prefs.Prefs
import com.keeler.foldfx.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Always-on foreground service that watches the hinge-angle sensor and
 * drives the fold/unfold transition overlay.
 *
 * Hinge angle 0° (closed) -> 180° (flat) is mapped to an effect progress
 * that peaks mid-fold: 0 when settled, 1 around 90°.
 */
class FoldEffectService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var hinge: HingeMonitor
    private lateinit var overlays: FoldOverlayManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        hinge = HingeMonitor(this)
        overlays = FoldOverlayManager(applicationContext).apply {
            setEffectId(prefs.effectId)
            intensity = prefs.intensity
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE, ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (!hinge.hasHingeSensor) {
            // v1 needs the continuous hinge sensor; without it there is
            // nothing smooth to drive the effect with.
            stopSelf()
            return START_NOT_STICKY
        }

        hinge.start()
        overlays.start()
        scope.launch {
            hinge.angle.collectLatest { angle ->
                overlays.setProgress(angleToProgress(angle))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hinge.stop()
        overlays.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun angleToProgress(angle: Float?): Float {
        if (angle == null) return 0f
        return sin(PI.toFloat() * angle.coerceIn(0f, 180f) / 180f).coerceIn(0f, 1f)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FoldFX status", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val settingsIntent = Intent(this, MainActivity::class.java)
        val settingsPi = PendingIntent.getActivity(
            this, 0, settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pausePi = PendingIntent.getService(
            this, 1,
            Intent(this, FoldEffectService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FoldFX is watching the hinge")
            .setContentText("Fold or unfold your phone to see the transition effect.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(settingsPi)
            .addAction(R.drawable.ic_notification, "Pause", pausePi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PAUSE = "com.keeler.foldfx.action.PAUSE"
        const val ACTION_STOP = "com.keeler.foldfx.action.STOP"
        private const val CHANNEL_ID = "foldfx_status"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FoldEffectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FoldEffectService::class.java))
        }
    }
}
