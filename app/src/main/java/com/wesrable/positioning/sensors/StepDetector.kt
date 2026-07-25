package com.wesrable.positioning.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits one estimated step length (meters) per detected footstep, for
 * pedestrian dead reckoning, via a peak-detection algorithm this app fully
 * controls rather than the OS's hardware `TYPE_STEP_DETECTOR` sensor. That
 * sensor's behavior varies by manufacturer firmware — many require a
 * "warm-up" period of several consistent steps before they start reporting,
 * and some stop reporting under conditions the app can't see or influence.
 * Running our own detector trades that opacity for full control.
 *
 * Prefers `TYPE_LINEAR_ACCELERATION` (gravity already removed by the OS's
 * sensor fusion), which makes the detection threshold independent of how
 * the phone is tilted or held — a fixed threshold on raw
 * `TYPE_ACCELEROMETER` has to account for gravity shifting the per-axis
 * baseline as orientation changes, a large part of why under-tuned
 * thresholds miss real steps. Falls back to raw `TYPE_ACCELEROMETER` only on
 * devices without linear acceleration available (typically ones lacking a
 * gyroscope). Nothing here touches a radio.
 */
class StepDetector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        const val DEFAULT_STEP_LENGTH_METERS = 0.75f
        const val WEINBERG_K = 0.5f
        const val MIN_STEP_INTERVAL_MILLIS = 250L

        // Linear acceleration has gravity already removed, so it sits near
        // 0 m/s² at rest regardless of phone orientation — a small, fixed
        // threshold is enough, and stays valid across carry positions.
        const val LINEAR_PEAK_THRESHOLD_MS2 = 1.5f
        const val LINEAR_TROUGH_THRESHOLD_MS2 = 0.6f

        // Raw accelerometer still includes ~9.8 m/s² of gravity, whose
        // contribution to each axis shifts with phone tilt, so this needs a
        // threshold well above that baseline instead.
        const val RAW_PEAK_THRESHOLD_MS2 = 11.5f
        const val RAW_TROUGH_THRESHOLD_MS2 = 9.0f
    }

    fun steps(): Flow<Float> = callbackFlow {
        when {
            linearAccelerometer != null -> peakDetect(
                linearAccelerometer,
                LINEAR_PEAK_THRESHOLD_MS2,
                LINEAR_TROUGH_THRESHOLD_MS2,
            )
            rawAccelerometer != null -> peakDetect(
                rawAccelerometer,
                RAW_PEAK_THRESHOLD_MS2,
                RAW_TROUGH_THRESHOLD_MS2,
            )
            else -> awaitClose { }
        }
    }

    private suspend fun ProducerScope<Float>.peakDetect(
        sensor: Sensor,
        peakThreshold: Float,
        troughThreshold: Float,
    ) {
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

                if (magnitude > peakThreshold) {
                    risingEdge = true
                } else if (risingEdge && magnitude < troughThreshold) {
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
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
