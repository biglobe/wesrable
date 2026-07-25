package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pedestrian dead reckoning: integrates (step length, heading) pairs into a
 * running 2D position, relative to wherever tracking started. This works
 * fully offline with zero radios — pure accelerometer + gyroscope/magnetometer.
 * Error accumulates with distance travelled (typically ~5% of path length),
 * so it's best used as a fallback/complement to RF-based fixes, not a
 * standalone absolute positioning system.
 */
class DeadReckoningTracker {

    private var x = 0.0
    private var y = 0.0
    private var stepCount = 0
    private var headingOffsetDeg = 0f

    /** Total footsteps counted since the tracker was created or last [reset]. */
    val totalSteps: Int get() = stepCount

    fun reset() {
        x = 0.0
        y = 0.0
        stepCount = 0
    }

    /**
     * The raw device-attitude heading (which way the *top of the phone* is
     * pointing) only matches the direction someone is actually walking if
     * they hold the phone upright with its top pointed straight ahead — tilt
     * or hold it more casually (very common while glancing at the screen)
     * and the two can diverge by any amount, including a full reversal.
     * Call this while walking in a known direction (e.g. "I'm facing
     * forward right now") to lock in an offset that corrects for however
     * the phone is actually being held, so subsequent steps track true
     * walking direction instead of raw device attitude.
     */
    fun calibrateHeading(currentRawHeadingDeg: Float) {
        headingOffsetDeg = -currentRawHeadingDeg
    }

    /** Call once per detected footstep with the current raw compass heading in degrees. */
    fun onStep(stepLengthMeters: Float, headingDegrees: Float): PositionEstimate {
        val correctedHeadingDeg = headingDegrees + headingOffsetDeg
        val headingRad = Math.toRadians(correctedHeadingDeg.toDouble())
        x += stepLengthMeters * sin(headingRad)
        y += stepLengthMeters * cos(headingRad)
        stepCount++

        // Confidence widens with distance travelled to reflect drift.
        val distanceTravelled = stepCount * stepLengthMeters
        val confidence = 0.3 + 0.05 * distanceTravelled

        return PositionEstimate(
            xMeters = x,
            yMeters = y,
            source = PositionSource.DEAD_RECKONING,
            confidenceRadiusMeters = confidence,
        )
    }

    fun currentPosition() = PositionEstimate(x, y, PositionSource.DEAD_RECKONING, 0.0)
}
