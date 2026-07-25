package com.wesrable.positioning.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.wesrable.positioning.model.BarometricReading
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Barometric altitude, used only in *relative* mode: floors are counted from
 * wherever the app started, using a ~3 m (~0.36 hPa) storey height. No absolute
 * sea-level calibration or network-fetched reference pressure is used.
 */
class BarometerSensor(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val isAvailable: Boolean get() = pressureSensor != null

    private companion object {
        const val FLOOR_HEIGHT_METERS = 3.0f
    }

    fun readings(): Flow<BarometricReading> = callbackFlow {
        var baselineAltitude: Float? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val pressureHpa = event.values[0]
                val altitude = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    pressureHpa,
                )
                if (baselineAltitude == null) baselineAltitude = altitude

                val relativeAltitude = altitude - (baselineAltitude ?: altitude)
                val floor = (relativeAltitude / FLOOR_HEIGHT_METERS).roundToInt()

                trySend(
                    BarometricReading(
                        pressureHpa = pressureHpa,
                        altitudeMeters = relativeAltitude,
                        relativeFloor = floor,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        pressureSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
