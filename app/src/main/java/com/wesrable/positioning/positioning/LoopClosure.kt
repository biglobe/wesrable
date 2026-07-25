package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.LoopClosure
import com.wesrable.positioning.model.MapWaypoint
import com.wesrable.positioning.model.TrailWaypoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Recognises when the walker has returned to somewhere they have already
 * been, so the drift accumulated in between can be corrected — the *loop
 * closure* step that makes a robot vacuum's map come out square instead of
 * spiralling away from itself.
 *
 * Every few meters a [TrailWaypoint] is recorded: the RSSI of everything
 * audible plus the ambient magnetic field, tagged with the dead-reckoned
 * position at the time. Each new waypoint is compared against the earlier
 * ones; a close signal match means the two are the same physical place, and
 * whatever gap has opened up between their recorded coordinates is pure
 * accumulated error.
 *
 * Nothing here touches Android, so it can be exercised directly on the JVM.
 */
class LoopClosureTracker(
    private val spacingMeters: Double = WAYPOINT_SPACING_METERS,
) {

    companion object {
        /** How far to walk between recorded waypoints. */
        const val WAYPOINT_SPACING_METERS = 2.5

        /**
         * How far the walker must have travelled since a waypoint before a
         * match against it counts as a *revisit*.
         *
         * Partly this stops the waypoint a few paces back from matching best
         * — correctly, since it really is nearby — and pinning the trail in
         * place. Mostly, though, it is about whether closing the loop is
         * worth anything: dead reckoning drifts by roughly 5% of distance
         * walked, so under ~60 m the accumulated error is smaller than the
         * match resolution and "correcting" it only adds noise. In simulation
         * this is exactly what happens — below this figure short routes come
         * out worse, at 60 m they are left untouched, and the long routes
         * that genuinely need correcting still get it.
         */
        const val MIN_LOOP_PATH_METERS = 60.0

        /**
         * Weighted RSSI distance below which two waypoints are treated as the
         * same place.
         *
         * This is the loosest part of the whole technique, and the number is
         * low for a reason. Signal distance grows only logarithmically with
         * separation while RSSI noise at a *fixed* spot is several dB, so the
         * two distributions overlap heavily: simulated at a realistic +/-5 dB,
         * standing still gives a median distance of ~3.0 and walking 12 m
         * away gives only ~6.2. A threshold of 8 — which looks reasonable
         * next to [MISSING_SIGNAL_PENALTY_DB] — actually accepts waypoints
         * 20 m apart as "the same place".
         */
        const val MATCH_THRESHOLD = 4.0

        /** Applied per landmark seen at one waypoint but not the other. */
        const val MISSING_SIGNAL_PENALTY_DB = 15.0

        /**
         * Dead reckoning drifts by roughly 5% of distance walked, so the
         * plausible correction scales with the size of the loop. A demand to
         * shift much further than that is far more likely a bad match than a
         * real error, and applying it would wreck an otherwise good trail.
         */
        const val MAX_DRIFT_FRACTION_OF_LOOP = 0.15
        const val MIN_PLAUSIBLE_DRIFT_METERS = 8.0

        /**
         * Corrections smaller than this are discarded rather than applied.
         *
         * A closure says "you are back at that waypoint", but only to within
         * its own resolution, and per [MATCH_THRESHOLD] that resolution is
         * several meters — the signal match cannot do better. So the gap the
         * closure measures is not pure drift: it is drift *plus* however far
         * apart the two waypoints really are. Applying it when the trail has
         * drifted by less than that trades a small real error for a
         * comparable invented one, which in simulation made short-loop routes
         * measurably worse rather than better. Below this the honest answer
         * is that the trail is already as good as this technique can tell.
         */
        const val MIN_CORRECTION_METERS = 5.0

        /**
         * After closing a loop, walk this far before trying again. Once
         * corrected, the following waypoints all sit near matches too, and
         * without a pause the trail would be nudged on every single one.
         */
        const val CLOSURE_COOLDOWN_METERS = 6.0

        private const val WIFI_WEIGHT = 1.0
        private const val BLE_WEIGHT = 1.0
        private const val MAGNETIC_WEIGHT = 0.5
    }

    private val waypoints = mutableListOf<TrailWaypoint>()
    private var lastWaypointAtPathLength = Double.NEGATIVE_INFINITY
    private var lastClosureAtPathLength = Double.NEGATIVE_INFINITY

    val waypointCount: Int get() = waypoints.size

    /** Positions where a loop was closed, for drawing on the map. */
    val closurePoints: List<Pair<Double, Double>> get() = closures.toList()
    private val closures = mutableListOf<Pair<Double, Double>>()

    fun reset() {
        waypoints.clear()
        closures.clear()
        lastWaypointAtPathLength = Double.NEGATIVE_INFINITY
        lastClosureAtPathLength = Double.NEGATIVE_INFINITY
    }

    /**
     * Offers the current position and live signal snapshot. Records a new
     * waypoint if far enough from the last, and returns a [LoopClosure] when
     * that waypoint is recognised as a place already visited.
     */
    fun observe(
        xMeters: Double,
        yMeters: Double,
        pathLengthMeters: Double,
        wifiRssi: Map<String, Int>,
        bleRssi: Map<String, Int>,
        magneticMagnitudeUt: Float?,
        wifiScanGeneration: Long,
    ): LoopClosure? {
        if (pathLengthMeters - lastWaypointAtPathLength < spacingMeters) return null
        lastWaypointAtPathLength = pathLengthMeters

        val waypoint = TrailWaypoint(
            xMeters = xMeters,
            yMeters = yMeters,
            pathLengthMeters = pathLengthMeters,
            wifiRssi = wifiRssi,
            bleRssi = bleRssi,
            magneticMagnitudeUt = magneticMagnitudeUt,
            wifiScanGeneration = wifiScanGeneration,
        )
        waypoints.add(waypoint)

        if (pathLengthMeters - lastClosureAtPathLength < CLOSURE_COOLDOWN_METERS) return null

        val closure = findRevisit(waypoint) ?: return null
        lastClosureAtPathLength = pathLengthMeters
        closures.add(xMeters to yMeters)
        return closure
    }

    /**
     * Slides stored waypoints by the same rubber-sheet correction applied to
     * the trail, so later comparisons are made against corrected coordinates.
     */
    fun applyCorrection(
        anchorPathLength: Double,
        endPathLength: Double,
        driftEast: Double,
        driftNorth: Double,
    ) {
        val span = endPathLength - anchorPathLength
        if (span <= 0.0) return

        for (index in waypoints.indices) {
            val waypoint = waypoints[index]
            val fraction = when {
                waypoint.pathLengthMeters <= anchorPathLength -> 0.0
                waypoint.pathLengthMeters >= endPathLength -> 1.0
                else -> (waypoint.pathLengthMeters - anchorPathLength) / span
            }
            if (fraction == 0.0) continue
            waypoints[index] = waypoint.copy(
                xMeters = waypoint.xMeters - driftEast * fraction,
                yMeters = waypoint.yMeters - driftNorth * fraction,
            )
        }
    }

    /**
     * This session's waypoints in the form the persistent map keeps them:
     * position plus signature, with the path length dropped since it means
     * nothing once the walk that produced it is over.
     */
    fun waypointsForMap(): List<MapWaypoint> = waypoints.map {
        MapWaypoint(
            xMeters = it.xMeters,
            yMeters = it.yMeters,
            wifiRssi = it.wifiRssi,
            bleRssi = it.bleRssi,
            magneticMagnitudeUt = it.magneticMagnitudeUt,
        )
    }

    /** Slides every waypoint bodily, for a rebase onto a stored map. */
    fun translate(east: Double, north: Double) {
        for (index in waypoints.indices) {
            waypoints[index] = waypoints[index].copy(
                xMeters = waypoints[index].xMeters + east,
                yMeters = waypoints[index].yMeters + north,
            )
        }
        for (index in closures.indices) {
            closures[index] = closures[index].first + east to closures[index].second + north
        }
    }

    /** The earlier waypoint that best matches [candidate], if any is close enough. */
    private fun findRevisit(candidate: TrailWaypoint): LoopClosure? {
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE

        waypoints.forEachIndexed { index, earlier ->
            if (candidate.pathLengthMeters - earlier.pathLengthMeters < MIN_LOOP_PATH_METERS) {
                return@forEachIndexed
            }
            val distance = signalDistance(candidate, earlier) ?: return@forEachIndexed
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        if (bestIndex < 0 || bestDistance > MATCH_THRESHOLD) return null

        val match = waypoints[bestIndex]
        val driftEast = candidate.xMeters - match.xMeters
        val driftNorth = candidate.yMeters - match.yMeters

        val drift = hypot(driftEast, driftNorth)
        if (drift < MIN_CORRECTION_METERS) return null

        val loopLength = candidate.pathLengthMeters - match.pathLengthMeters
        val plausibleDrift =
            (MAX_DRIFT_FRACTION_OF_LOOP * loopLength).coerceAtLeast(MIN_PLAUSIBLE_DRIFT_METERS)
        if (drift > plausibleDrift) return null

        return LoopClosure(
            matchedIndex = bestIndex,
            anchorPathLengthMeters = match.pathLengthMeters,
            currentPathLengthMeters = candidate.pathLengthMeters,
            driftEastMeters = driftEast,
            driftNorthMeters = driftNorth,
        )
    }

    /**
     * Weighted mean of whichever signal comparisons can actually be made, or
     * null when there is no usable evidence either way. Averaging over the
     * available terms rather than summing keeps one threshold meaningful
     * whether or not, say, any BLE beacons happen to be in range.
     */
    private fun signalDistance(a: TrailWaypoint, b: TrailWaypoint): Double? {
        var weighted = 0.0
        var totalWeight = 0.0

        // Skipped when both readings came from the same throttled WiFi scan:
        // they would be identical whatever the walker did in between.
        if (a.wifiScanGeneration != b.wifiScanGeneration) {
            rssiDistance(a.wifiRssi, b.wifiRssi)?.let {
                weighted += WIFI_WEIGHT * it
                totalWeight += WIFI_WEIGHT
            }
        }
        rssiDistance(a.bleRssi, b.bleRssi)?.let {
            weighted += BLE_WEIGHT * it
            totalWeight += BLE_WEIGHT
        }
        if (a.magneticMagnitudeUt != null && b.magneticMagnitudeUt != null) {
            weighted += MAGNETIC_WEIGHT * abs(a.magneticMagnitudeUt - b.magneticMagnitudeUt)
            totalWeight += MAGNETIC_WEIGHT
        }

        return if (totalWeight == 0.0) null else weighted / totalWeight
    }

    /** RMS RSSI difference over the union of landmarks, or null if neither saw any. */
    private fun rssiDistance(a: Map<String, Int>, b: Map<String, Int>): Double? {
        val keys = a.keys + b.keys
        if (keys.isEmpty()) return null
        val sumSquares = keys.sumOf { key ->
            val left = a[key]
            val right = b[key]
            val difference = if (left != null && right != null) {
                (left - right).toDouble()
            } else {
                MISSING_SIGNAL_PENALTY_DB
            }
            difference * difference
        }
        return sqrt(sumSquares / keys.size)
    }
}
