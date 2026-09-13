package com.keeler.foldfx.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Null on devices without a hinge-angle sensor (older Folds report only coarse postures). */
fun SensorManager.hingeSensor(): Sensor? = getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

/**
 * Watches the hinge-angle sensor.
 *
 * Reports degrees: 0 = fully closed, 180 = fully flat. Emits null until the
 * first reading arrives. Devices without the sensor (older Folds report only
 * coarse postures) set [hasHingeSensor] false and never emit.
 */
class HingeMonitor(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val hingeSensor: Sensor? = sensorManager.hingeSensor()

    val hasHingeSensor: Boolean get() = hingeSensor != null

    private val _angle = MutableStateFlow<Float?>(null)
    val angle: StateFlow<Float?> = _angle

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            _angle.value = event.values[0]
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        hingeSensor?.let {
            // GAME rate (~20ms): latency between physical hinge and effect
            // matters more than rate, and the sensor hub barely notices.
            // Rendering is decoupled via Choreographer easing in the overlay
            // manager, so this never needs to match the display refresh.
            // Accepted tradeoff: the sensor streams 24/7 while enabled, even
            // parked at rest. Batching (maxReportLatencyUs) would cut wakeups
            // but add exactly the latency this effect can't afford.
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
