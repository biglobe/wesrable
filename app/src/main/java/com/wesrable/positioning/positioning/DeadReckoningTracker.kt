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

    /** Total footsteps counted since the tracker was created or last [reset]. */
    val totalSteps: Int get() = stepCount

    fun reset() {
        x = 0.0
        y = 0.0
        stepCount = 0
    }

    /** Call once per detected footstep with the current compass heading in degrees. */
    fun onStep(stepLengthMeters: Float, headingDegrees: Float): PositionEstimate {
        val headingRad = Math.toRadians(headingDegrees.toDouble())
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
