package com.keeler.foldfx.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    val hingeSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

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
            // SENSOR_DELAY_UI is plenty: hinge motion is slow, and it keeps
            // the sensor hub (and battery) happy while always-on.
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
