package com.keeler.foldfx.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.keeler.foldfx.overlay.effects.EffectCatalog
import com.keeler.foldfx.overlay.effects.FoldEffect
import com.keeler.foldfx.prefs.Prefs
import com.keeler.foldfx.service.FoldEffectService
import com.keeler.foldfx.service.hingeSensor

class MainActivity : ComponentActivity() {

    /** Bumped on every onResume so permission/pref state re-reads. */
    private var resumeTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                key(resumeTick) {
                    FoldFxSettingsScreen(EffectCatalog.all)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

/**
 * The live hinge angle, or null on devices without a hinge-angle sensor.
 * Null doubles as the "sensor missing" signal — no wrapper type needed.
 */
@Composable
private fun rememberHingeAngle(): MutableState<Float?>? {
    val context = LocalContext.current
    val angle = remember { mutableStateOf<Float?>(null) }
    val hasSensor = remember {
        context.getSystemService(SensorManager::class.java).hingeSensor() != null
    }
    DisposableEffect(hasSensor) {
        if (!hasSensor) return@DisposableEffect onDispose {}
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.hingeSensor() ?: return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                angle.value = e.values[0]
            }

            override fun onAccuracyChanged(s: Sensor, a: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return angle.takeIf { hasSensor }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldFxSettingsScreen(effects: List<FoldEffect>) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val hingeAngle = rememberHingeAngle()

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var effectId by remember { mutableStateOf(prefs.effectId) }
    var intensity by remember { mutableFloatStateOf(prefs.intensity) }

    val hasOverlayPermission = Settings.canDrawOverlays(context)
    val batteryExempt = remember {
        val pm = context.getSystemService(PowerManager::class.java)
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("FoldFX") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sensor status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Hinge sensor", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    if (hingeAngle != null) {
                        val angle = hingeAngle.value
                        Text(
                            if (angle == null) "Sensor found — waiting for first reading…"
                            else "Angle: ${angle.toInt()}°  (0 = closed, 180 = flat)",
                        )
                    } else {
                        Text("No hinge-angle sensor on this device. FoldFX needs the continuous sensor, so effects are unavailable here.")
                    }
                }
            }

            // Master switch
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Fold effects", style = MaterialTheme.typography.titleMedium)
                        Text("Shows a transition effect while folding or unfolding.")
                    }
                    Switch(
                        checked = enabled,
                        enabled = hingeAngle != null,
                        onCheckedChange = { on ->
                            if (on && !Settings.canDrawOverlays(context)) {
                                context.openOverlaySettings()
                                return@Switch
                            }
                            enabled = on
                            prefs.enabled = on
                            if (on) FoldEffectService.start(context)
                            else FoldEffectService.stop(context)
                        },
                    )
                }
            }

            // Effect picker
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Effect", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    effects.forEach { effect ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = effectId == effect.id,
                                    role = Role.RadioButton,
                                    onClick = {
                                        effectId = effect.id
                                        prefs.effectId = effect.id
                                        // Applies live to the running service, no restart.
                                        if (enabled) FoldEffectService.refresh(context)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = effectId == effect.id,
                                onClick = null,
                            )
                            Text(effect.displayName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            // Intensity
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Intensity", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = intensity,
                        onValueChange = {
                            intensity = it
                            prefs.intensity = it
                        },
                        // One refresh per gesture, not one per drag tick.
                        onValueChangeFinished = {
                            if (enabled) FoldEffectService.refresh(context)
                        },
                        valueRange = 0.5f..1.5f,
                    )
                }
            }

            // Permissions
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    PermissionRow(
                        label = "Display over other apps",
                        granted = hasOverlayPermission,
                        onGrant = { context.openOverlaySettings() },
                    )
                    PermissionRow(
                        label = "Ignore battery optimizations",
                        granted = batteryExempt,
                        onGrant = { context.requestBatteryExemption() },
                    )
                    Text(
                        "FoldFX keeps a lightweight foreground service running so it can catch the hinge the moment it moves.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "Experimental tech demo. Android — not FoldFX — decides when displays switch on/off, so the effect races the system transition and can't replace it. No drawing over the secure lock screen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (granted) {
            Text("Granted", color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

private fun Context.openOverlaySettings() {
    startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.requestBatteryExemption() {
    startActivity(
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
