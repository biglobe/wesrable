package com.wesrable.positioning.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits one estimated step length (meters) per detected footstep, for
 * pedestrian dead reckoning. Prefers the hardware TYPE_STEP_DETECTOR (a
 * low-power on-chip pedometer); falls back to accelerometer-magnitude peak
 * detection with the Weinberg dynamic step-length formula when the
 * dedicated sensor is absent. Nothing here touches a radio.
 */
class StepDetector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hardwareStepSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val DEFAULT_STEP_LENGTH_METERS = 0.75f
        const val WEINBERG_K = 0.5f
        const val PEAK_THRESHOLD_MS2 = 11.5f
        const val TROUGH_THRESHOLD_MS2 = 9.0f
        const val MIN_STEP_INTERVAL_MILLIS = 250L
    }

    fun steps(): Flow<Float> = callbackFlow {
        if (hardwareStepSensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(DEFAULT_STEP_LENGTH_METERS)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, hardwareStepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sensorManager.unregisterListener(listener) }
        } else if (accelerometer != null) {
            var windowMax = Float.MIN_VALUE
            var windowMin = Float.MAX_VALUE
            var risingEdge = false
            var lastStepAt = 0L

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val magnitude = sqrt(
                        event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2)
                    )
                    windowMax = maxOf(windowMax, magnitude)
                    windowMin = minOf(windowMin, magnitude)

                    if (magnitude > PEAK_THRESHOLD_MS2) {
                        risingEdge = true
                    } else if (risingEdge && magnitude < TROUGH_THRESHOLD_MS2) {
                        val now = event.timestamp / 1_000_000
                        if (now - lastStepAt > MIN_STEP_INTERVAL_MILLIS) {
                            val stepLength = WEINBERG_K * (windowMax - windowMin).toDouble().pow(0.25)
                            trySend(stepLength.toFloat().coerceIn(0.3f, 1.1f))
                            lastStepAt = now
                        }
                        risingEdge = false
                        windowMax = Float.MIN_VALUE
                        windowMin = Float.MAX_VALUE
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            awaitClose { sensorManager.unregisterListener(listener) }
        } else {
            awaitClose { }
        }
    }
}
