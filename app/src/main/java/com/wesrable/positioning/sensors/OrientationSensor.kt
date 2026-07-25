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
 * Device attitude, with heading taken from the gyroscope rather than the
 * compass.
 *
 * `TYPE_ROTATION_VECTOR` folds the magnetometer into its fusion, which is
 * right outdoors and wrong in a building: steel, wiring and appliances bend
 * the field by tens of degrees, so the reported heading swings from room to
 * room while the walker goes straight. Dead reckoning integrates that error
 * into the trail, and it shows up exactly as walking a circuit and not
 * arriving back where you started — the return leg rotated away from the
 * outbound one. It is the same distortion that makes magnetic fingerprinting
 * possible: a building distinctive enough to recognise magnetically is a
 * building whose compass cannot be trusted.
 *
 * `TYPE_GAME_ROTATION_VECTOR` is the same fusion with the magnetometer left
 * out. Its heading is relative to an arbitrary start and creeps with gyro
 * bias, but it does not care what the walls are made of, so over the minutes
 * a walk lasts it is far steadier than the compass.
 *
 * Neither alone is enough. Gyro-only heading drifts without bound and has no
 * idea where north is, which the persistent map needs — sessions can only
 * share a frame because their axes are north-referenced. So the two are
 * combined: turn rates come from the gyro, and the result is pulled towards
 * magnetic north slowly enough that a room full of steel cannot yank it, and
 * only while Android reports the compass as being worth listening to.
 */
class OrientationSensor(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val compassSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    val isAvailable: Boolean get() = compassSensor != null || gyroSensor != null

    private companion object {
        /**
         * How long to let the compass set north before largely ignoring it.
         *
         * North only has to be established *once*: it is needed so that
         * sessions share a frame with the stored map, not to steer the walk.
         * So the compass is given real authority at the start, while the
         * device is likely still and the reading has not yet been contradicted,
         * and almost none afterwards.
         */
        const val ALIGNMENT_MILLIS = 4_000L

        /** Time constant of roughly 1 s, to settle on north quickly at first. */
        const val ALIGNMENT_GAIN = 0.02f

        /**
         * Fraction of the compass/gyro disagreement removed per sample once
         * aligned — a time constant of about 200 s.
         *
         * It was twenty times faster than this, on the reasoning that it
         * should be slow enough for walking past a fridge to move the heading
         * only a fraction of a degree. That reasoning holds for one
         * disturbance passed once. It fails badly on a small circuit walked
         * repeatedly, where the whole path sits inside a distorted field and
         * the compass tells a different story in every corner: over a
         * minute-long walk the filter had time to follow it, and successive
         * laps came out rotated from each other rather than on top.
         *
         * Slow enough now to be a leash on gyro bias over many minutes rather
         * than anything that steers a walk.
         */
        const val NORTH_CORRECTION_GAIN = 0.00005f

        /**
         * Below this the platform is telling us the magnetometer needs
         * calibrating, and its heading is not worth pulling towards at all.
         */
        const val MIN_USABLE_COMPASS_ACCURACY = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    }

    fun readings(): Flow<Orientation> = callbackFlow {
        val rotationMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)

        // Heading actually reported: gyro turn rates, nudged towards north.
        var fusedAzimuth: Float? = null
        var lastGyroAzimuth: Float? = null
        var compassAzimuth = 0f
        var compassAccuracy = 0
        /** When north-seeking began, so it can be wound down after a few seconds. */
        var alignmentStartedAt: Long? = null
        var pitch = 0f
        var roll = 0f

        fun azimuthOf(event: SensorEvent): Float {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationValues)
            pitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
            roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
            val degrees = Math.toDegrees(orientationValues[0].toDouble())
            return (if (degrees < 0) degrees + 360 else degrees).toFloat()
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        compassAzimuth = azimuthOf(event)
                        compassAccuracy = event.accuracy
                        // With no gyro to lead, the compass is all there is.
                        if (gyroSensor == null) {
                            fusedAzimuth = compassAzimuth
                            emit()
                        } else if (fusedAzimuth == null) {
                            // Start out pointing wherever north is, so the
                            // trail shares its axes with any stored map.
                            fusedAzimuth = compassAzimuth
                        }
                    }

                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        val gyroAzimuth = azimuthOf(event)
                        val previous = lastGyroAzimuth
                        lastGyroAzimuth = gyroAzimuth

                        if (alignmentStartedAt == null && fusedAzimuth != null) {
                            alignmentStartedAt = event.timestamp / 1_000_000
                        }
                        val current = fusedAzimuth
                        if (current == null) {
                            // No compass fix yet; nothing to anchor to.
                            if (compassSensor == null) fusedAzimuth = gyroAzimuth
                            return
                        }
                        // Turn by however much the gyro says we turned.
                        var updated = current + (if (previous == null) 0f else difference(gyroAzimuth, previous))
                        if (compassAccuracy >= MIN_USABLE_COMPASS_ACCURACY) {
                            val elapsed = (event.timestamp / 1_000_000) - (alignmentStartedAt ?: 0L)
                            val aligning = alignmentStartedAt != null && elapsed < ALIGNMENT_MILLIS
                            val gain = if (aligning) ALIGNMENT_GAIN else NORTH_CORRECTION_GAIN
                            updated += gain * difference(compassAzimuth, updated)
                        }
                        fusedAzimuth = normalize(updated)
                        emit()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) compassAccuracy = accuracy
            }

            private fun emit() {
                trySend(
                    Orientation(
                        azimuthDeg = fusedAzimuth ?: return,
                        pitchDeg = pitch,
                        rollDeg = roll,
                        accuracy = compassAccuracy,
                    )
                )
            }
        }

        compassSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /** Shortest signed turn from [from] to [to], in (-180, 180]. */
    private fun difference(to: Float, from: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta <= -180f) delta += 360f
        return delta
    }

    private fun normalize(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0) value += 360f
        return value
    }
}
