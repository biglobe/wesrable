package com.wesrable.positioning.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits one estimated step length (meters) per confirmed footstep, for
 * pedestrian dead reckoning, using an algorithm this app fully controls
 * rather than the OS's hardware `TYPE_STEP_DETECTOR` sensor. That sensor's
 * behavior varies by manufacturer firmware — many require a "warm-up" period
 * of several consistent steps before they start reporting, and some stop
 * reporting under conditions the app can't see or influence.
 *
 * Two properties of walking are exploited to tell real gait apart from the
 * phone simply being waved around, which a naive threshold on overall
 * acceleration magnitude cannot do:
 *
 *  1. **Gait is vertical.** Walking bounces your center of mass up and down
 *     against gravity; idle hand movement is mostly lateral and rotational.
 *     So every sample is projected onto the gravity vector and only that
 *     vertical component drives detection. Taking the magnitude of the raw
 *     3-axis vector instead — the obvious approach — is direction-blind, and
 *     rectifies a shake along *any* axis into an apparent step.
 *  2. **Gait is rhythmic and sustained.** Peaks are only committed once
 *     [GaitAnalyzer.STEPS_TO_CONFIRM_BOUT] of them arrive in a row at a
 *     steady, plausible walking cadence; isolated bursts of fidgeting never
 *     reach that bar. Confirmation is retroactive, so no real step is lost to
 *     the wait.
 *
 * Prefers the fused `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY` pair, which
 * separate gravity from motion using the gyroscope. Falls back to raw
 * `TYPE_ACCELEROMETER` with a low-pass gravity estimate on devices lacking
 * them. Nothing here touches a radio, and none of it needs
 * `ACTIVITY_RECOGNITION`.
 */
class StepDetector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rawAccelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private companion object {
        /**
         * Weight of the previous estimate in the low-pass filter that pulls
         * gravity back out of the raw accelerometer. At ~50 Hz this is a time
         * constant of ~0.4 s (≈0.4 Hz corner), well below the ~2 Hz of
         * walking, so gait energy stays in the residual rather than leaking
         * into the "gravity" term.
         */
        const val GRAVITY_SMOOTHING = 0.95f

        /** Below this the gravity vector is garbage (free fall, or not yet settled). */
        const val MIN_GRAVITY_MAGNITUDE_MS2 = 1f
    }

    fun steps(): Flow<Float> = callbackFlow {
        val analyzer = GaitAnalyzer { stepLength -> trySend(stepLength) }
        when {
            linearAccelerometer != null && gravitySensor != null ->
                collectFused(linearAccelerometer, gravitySensor, analyzer)

            rawAccelerometer != null -> collectRaw(rawAccelerometer, analyzer)
            else -> awaitClose { }
        }
    }

    /** Gravity and linear acceleration both straight from the OS's sensor fusion. */
    private suspend fun ProducerScope<Float>.collectFused(
        linear: Sensor,
        gravity: Sensor,
        analyzer: GaitAnalyzer,
    ) {
        // Unit vector pointing away from the earth, in device coordinates.
        var up: FloatArray? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        val magnitude = magnitudeOf(event.values)
                        if (magnitude >= MIN_GRAVITY_MAGNITUDE_MS2) {
                            up = floatArrayOf(
                                event.values[0] / magnitude,
                                event.values[1] / magnitude,
                                event.values[2] / magnitude,
                            )
                        }
                    }

                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val vertical = up?.let { dot(event.values, it) } ?: return
                        analyzer.onSample(vertical, event.timestamp / 1_000_000)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, linear, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /**
     * Single-sensor fallback: low-pass the raw accelerometer to estimate
     * gravity, subtract it to get linear acceleration, then project that back
     * onto the gravity direction.
     */
    private suspend fun ProducerScope<Float>.collectRaw(
        accelerometer: Sensor,
        analyzer: GaitAnalyzer,
    ) {
        val gravity = FloatArray(3)
        var seeded = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!seeded) {
                    // Seed from the first sample so the filter doesn't spend
                    // its first second climbing from zero and reporting the
                    // whole of gravity as motion.
                    for (axis in 0..2) gravity[axis] = event.values[axis]
                    seeded = true
                    return
                }
                for (axis in 0..2) {
                    gravity[axis] = GRAVITY_SMOOTHING * gravity[axis] +
                        (1 - GRAVITY_SMOOTHING) * event.values[axis]
                }

                val magnitude = magnitudeOf(gravity)
                if (magnitude < MIN_GRAVITY_MAGNITUDE_MS2) return

                var vertical = 0f
                for (axis in 0..2) {
                    vertical += (event.values[axis] - gravity[axis]) * (gravity[axis] / magnitude)
                }
                analyzer.onSample(vertical, event.timestamp / 1_000_000)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}

private fun magnitudeOf(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

/**
 * Turns a stream of vertical-acceleration samples into confirmed footsteps.
 *
 * A peak that clears [PEAK_THRESHOLD_MS2] and then falls back through
 * [TROUGH_THRESHOLD_MS2] is a *candidate*, not yet a step. Candidates are only
 * emitted once [STEPS_TO_CONFIRM_BOUT] of them have arrived in a row at a
 * steady, plausible walking cadence — at which point the whole run is emitted
 * at once, so the confirmation delay costs no steps. While that rhythm holds,
 * each subsequent candidate is emitted immediately; once the walker stops (a
 * gap longer than [MAX_STEP_INTERVAL_MILLIS]), the next bout has to earn
 * confirmation again.
 */
private class GaitAnalyzer(private val onStep: (Float) -> Unit) {

    private class Candidate(val atMillis: Long, val stepLength: Float)

    companion object {
        /**
         * Walking with the phone in hand swings the vertical component by
         * roughly ±1–3 m/s². The hysteresis gap between the two thresholds
         * stops a single noisy peak from registering repeatedly as it rings
         * down.
         */
        const val PEAK_THRESHOLD_MS2 = 1.0f
        const val TROUGH_THRESHOLD_MS2 = 0.3f

        /**
         * 2.5 steps/s, quicker than any real walking stride. Deliberately not
         * lower: at 300 ms a 3 Hz shake lands inside the plausible band and
         * reads as perfectly steady fast "walking".
         */
        const val MIN_STEP_INTERVAL_MILLIS = 400L

        /**
         * Slowest stride still treated as walking. Generous, because a step
         * out of a standing start is much slower than the cruise that
         * follows: at 1 s this rejected the first step or two of every burst,
         * which around a home is most of the walking there is.
         */
        const val MAX_STEP_INTERVAL_MILLIS = 1_800L

        /**
         * How far one stride may differ from *the one before it*.
         *
         * Comparing neighbours rather than requiring the whole window to
         * agree is the point: leaving a standstill the intervals shorten step
         * by step, and arriving they lengthen, so a window test forbids
         * precisely the accelerating and decelerating bursts that indoor
         * walking is made of. A trend is gait; scatter is not.
         *
         * The figure is unchanged from the window test it replaces — what
         * changed is the question being asked, which is why recall improved
         * without the gate being loosened.
         */
        const val CADENCE_TOLERANCE = 0.20f

        /**
         * How far a single stride may stray from the established cadence
         * before the bout is considered broken. Looser than
         * [CADENCE_TOLERANCE], because a real walker does vary — but still
         * enforced, since without it one lucky confirmation would let
         * arbitrarily irregular motion keep counting forever.
         */
        const val IN_BOUT_TOLERANCE = 0.20f

        /** Weight of history in the running estimate of the walker's cadence. */
        const val CADENCE_SMOOTHING = 0.7f

        /**
         * Steps that must line up before any are counted.
         *
         * Four rejects fidgeting well but never registers a walk shorter than
         * four steps — and moving around a home is largely three-step bursts
         * between turns, so it silently discarded much of the real walking.
         * Three is the compromise; the cost is paid back by the tolerance
         * above being trend-aware rather than loosened outright.
         */
        const val STEPS_TO_CONFIRM_BOUT = 3

        /**
         * Consecutive off-cadence strides tolerated before the walk is
         * treated as over. One alone is usually a turn or a doorway rather
         * than a stop, and ending the bout there costs the next three steps
         * while it re-confirms.
         */
        const val MAX_BOUT_MISSES = 2

        const val WEINBERG_K = 0.5f
        const val MIN_STEP_LENGTH_METERS = 0.3f
        const val MAX_STEP_LENGTH_METERS = 1.1f
    }

    private var windowMax = Float.NEGATIVE_INFINITY
    private var windowMin = Float.POSITIVE_INFINITY
    private var abovePeak = false

    /** Null until the first candidate; there is no interval to judge before that. */
    private var lastCandidateAt: Long? = null

    private val pending = mutableListOf<Candidate>()
    private var cadenceMillis: Float? = null
    private var boutMisses = 0

    fun onSample(vertical: Float, atMillis: Long) {
        if (vertical > windowMax) windowMax = vertical
        if (vertical < windowMin) windowMin = vertical

        if (vertical > PEAK_THRESHOLD_MS2) {
            abovePeak = true
            return
        }
        if (!abovePeak || vertical > TROUGH_THRESHOLD_MS2) return

        // Falling edge back through the trough — one candidate footstep.
        abovePeak = false
        val swing = (windowMax - windowMin).coerceAtLeast(0f)
        windowMax = Float.NEGATIVE_INFINITY
        windowMin = Float.POSITIVE_INFINITY

        val interval = lastCandidateAt?.let { atMillis - it }

        // Too soon to be a separate stride. The timestamp is still recorded:
        // if it weren't, a fast rhythmic shake would have every other peak
        // rejected and the survivors would land a plausible stride apart —
        // frequency-dividing a 4 Hz fidget into convincing 2 Hz "gait".
        if (interval != null && interval < MIN_STEP_INTERVAL_MILLIS) {
            lastCandidateAt = atMillis
            return
        }
        lastCandidateAt = atMillis

        // Weinberg: step length scales with the fourth root of the vertical
        // acceleration swing, so heavier strides are counted as longer ones.
        val stepLength = (WEINBERG_K * swing.toDouble().pow(0.25)).toFloat()
            .coerceIn(MIN_STEP_LENGTH_METERS, MAX_STEP_LENGTH_METERS)

        val cadence = cadenceMillis
        if (interval != null && cadence != null && interval <= MAX_STEP_INTERVAL_MILLIS) {
            val strayed = abs(interval - cadence) / cadence
            if (strayed <= IN_BOUT_TOLERANCE) {
                boutMisses = 0
                cadenceMillis = CADENCE_SMOOTHING * cadence + (1 - CADENCE_SMOOTHING) * interval
                onStep(stepLength)
                return
            }
            // Off cadence, but a turn or a doorway looks like this too. Count
            // it and follow the new pace rather than abandoning the walk on
            // the first stride that does not fit.
            boutMisses++
            if (boutMisses < MAX_BOUT_MISSES) {
                cadenceMillis = CADENCE_SMOOTHING * cadence + (1 - CADENCE_SMOOTHING) * interval
                onStep(stepLength)
                return
            }
        }
        if (cadenceMillis != null) {
            // Stopped, or the rhythm fell apart — earn confirmation again.
            cadenceMillis = null
            boutMisses = 0
        }

        if (interval == null || interval > MAX_STEP_INTERVAL_MILLIS) pending.clear()
        pending.add(Candidate(atMillis, stepLength))
        if (pending.size > STEPS_TO_CONFIRM_BOUT) pending.removeAt(0)

        if (pending.size == STEPS_TO_CONFIRM_BOUT) {
            val confirmed = steadyCadenceOf(pending)
            if (confirmed != null) {
                pending.forEach { onStep(it.stepLength) }
                pending.clear()
                cadenceMillis = confirmed
            }
        }
    }

    /**
     * The stride interval to start the walk at, when every gap between
     * [candidates] is a plausible stride *and* each follows on from the one
     * before it closely enough to be the same walk; null when they don't look
     * like walking.
     *
     * Each interval is compared with its neighbour rather than all of them
     * with each other, so a cadence that steadily quickens or slows still
     * reads as one walk. That is not a loophole — accelerating away from a
     * standstill and slowing into a stop is what a burst of indoor walking
     * *is*, and demanding the whole window agree rejected it wholesale.
     *
     * The most recent interval is returned rather than the mean, since after
     * an acceleration the latest pace is the one about to continue.
     */
    private fun steadyCadenceOf(candidates: List<Candidate>): Float? {
        val intervals = candidates.zipWithNext { earlier, later -> later.atMillis - earlier.atMillis }
        if (intervals.isEmpty()) return null
        if (intervals.any { it < MIN_STEP_INTERVAL_MILLIS || it > MAX_STEP_INTERVAL_MILLIS }) {
            return null
        }
        val consistent = intervals.zipWithNext().all { (earlier, later) ->
            abs(later - earlier) <= CADENCE_TOLERANCE * maxOf(earlier, later)
        }
        if (!consistent) return null
        return intervals.last().toFloat()
    }
}
