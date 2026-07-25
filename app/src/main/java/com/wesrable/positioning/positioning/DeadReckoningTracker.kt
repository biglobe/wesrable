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
 * standalone absolute positioning system — or corrected in place when the
 * walker is recognised to have revisited a spot, via [rubberSheet].
 */
class DeadReckoningTracker {

    /** One visited position, with the distance walked to reach it. */
    private data class TrailPoint(
        var x: Double,
        var y: Double,
        val pathLength: Double,
    )

    private var x = 0.0
    private var y = 0.0
    private var stepCount = 0
    private var pathLength = 0.0
    private val trailPoints = mutableListOf(TrailPoint(0.0, 0.0, 0.0))

    /** Total footsteps counted since the tracker was created or last [reset]. */
    val totalSteps: Int get() = stepCount

    /** Total distance walked, in meters — the length of the path, not the displacement. */
    val pathLengthMeters: Double get() = pathLength

    /** Every (east, north) position visited so far, oldest first, including the start point. */
    val trail: List<Pair<Double, Double>> get() = trailPoints.map { it.x to it.y }

    fun reset() {
        x = 0.0
        y = 0.0
        stepCount = 0
        pathLength = 0.0
        trailPoints.clear()
        trailPoints.add(TrailPoint(0.0, 0.0, 0.0))
    }

    /**
     * Call once per detected footstep with the current compass heading in
     * degrees. (East, North) is accumulated using the true compass bearing;
     * anything display-relative (e.g. "up on screen" meaning "the direction
     * I'm currently facing" rather than north) is the renderer's job — see
     * `PositionCard`'s heading-up projection — not this tracker's.
     */
    fun onStep(stepLengthMeters: Float, headingDegrees: Float): PositionEstimate {
        val headingRad = Math.toRadians(headingDegrees.toDouble())
        x += stepLengthMeters * sin(headingRad)
        y += stepLengthMeters * cos(headingRad)
        stepCount++
        pathLength += stepLengthMeters
        trailPoints.add(TrailPoint(x, y, pathLength))

        // Confidence widens with distance travelled to reflect drift.
        val confidence = 0.3 + 0.05 * pathLength

        return PositionEstimate(
            xMeters = x,
            yMeters = y,
            source = PositionSource.DEAD_RECKONING,
            confidenceRadiusMeters = confidence,
        )
    }

    /**
     * Removes an accumulated drift of ([driftEast], [driftNorth]) that built
     * up between [anchorPathLength] and [endPathLength], by sliding the trail
     * between those two points progressively further back into line — the
     * classic "rubber sheeting" used for a single loop-closure constraint.
     *
     * Points recorded before the anchor are left untouched (they predate the
     * drift), points after the loop shift by the whole error (it had already
     * fully accumulated by then), and points in between shift in proportion
     * to how far along the loop they were walked. That keeps the shape of the
     * path locally intact while pulling the two ends together, instead of
     * putting a visible kink at the single point where the error was noticed.
     *
     * Only translation is corrected. A single "these two points are the same
     * place" constraint doesn't observe rotation, so heading drift — which is
     * usually the larger error in dead reckoning — survives this. Correcting
     * it needs either several closures or a full pose-graph optimisation.
     */
    fun rubberSheet(
        anchorPathLength: Double,
        endPathLength: Double,
        driftEast: Double,
        driftNorth: Double,
    ) {
        val span = endPathLength - anchorPathLength
        if (span <= 0.0) return

        trailPoints.forEach { point ->
            val fraction = when {
                point.pathLength <= anchorPathLength -> 0.0
                point.pathLength >= endPathLength -> 1.0
                else -> (point.pathLength - anchorPathLength) / span
            }
            point.x -= driftEast * fraction
            point.y -= driftNorth * fraction
        }

        // The live position is the far end of the loop, so it takes the full
        // correction — that is the whole point of closing the loop.
        x -= driftEast
        y -= driftNorth
    }

    fun currentPosition() = PositionEstimate(x, y, PositionSource.DEAD_RECKONING, 0.0)
}
