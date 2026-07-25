package com.wesrable.positioning.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.wesrable.positioning.model.Orientation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * High-resolution device attitude using the fused TYPE_ROTATION_VECTOR sensor
 * (accelerometer + gyroscope + magnetometer combined on-chip). This gives
 * sub-degree heading/tilt resolution without touching any radio.
 */
class OrientationSensor(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = rotationSensor != null

    fun readings(): Flow<Orientation> = callbackFlow {
        val rotationMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)

                val azimuth = Math.toDegrees(orientationValues[0].toDouble()).let {
                    if (it < 0) it + 360 else it
                }
                val pitch = Math.toDegrees(orientationValues[1].toDouble())
                val roll = Math.toDegrees(orientationValues[2].toDouble())

                trySend(
                    Orientation(
                        azimuthDeg = azimuth.toFloat(),
                        pitchDeg = pitch.toFloat(),
                        rollDeg = roll.toFloat(),
                        accuracy = event.accuracy,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
