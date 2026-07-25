package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.MapWaypoint
import com.wesrable.positioning.model.Relocalization
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Works out where the current session sits inside a map recorded in earlier
 * ones.
 *
 * A session's coordinates start wherever the app happened to be launched, so
 * on its own the trail cannot be compared with anything stored. Recovering
 * the link is easier than it first appears: east and north are integrated
 * from the compass, so every session already shares the stored map's
 * orientation and only the origin differs. There is a translation to find and
 * no rotation, which halves the problem and removes its worst ambiguity.
 *
 * The translation is found by recognising places. Live signals are matched
 * against the stored waypoints; the best few vote on where in the map the
 * walker is standing, and the difference between that and the session's own
 * idea of where it is gives a candidate offset. One candidate is not trusted:
 * RSSI matching is only good to a few meters and occasionally points
 * somewhere else entirely, so offsets are collected as the walker moves and
 * the lock is taken only once several successive ones agree.
 *
 * No Android dependencies, so the accuracy of all this can be measured on the
 * JVM rather than guessed at.
 */
class Relocalizer(private val waypoints: List<MapWaypoint>) {

    companion object {
        /** How many stored waypoints vote on the live position. */
        const val K_NEIGHBORS = 5

        /**
         * Weighted signal distance beyond which the nearest stored waypoint
         * is not considered a match at all. Same scale as the room matcher's
         * gate, where ~3 means standing on the spot and ~6 about 12 m away.
         */
        const val MAX_MATCH_DISTANCE = 5.0

        /**
         * The winner must beat the median candidate by this factor. Without
         * it a place that resembles the whole building equally — or a
         * building whose signals barely vary — yields a confident-looking
         * answer built from nothing.
         */
        const val DISTINCTIVENESS_RATIO = 0.6

        /**
         * If the voting waypoints are scattered further apart than this they
         * disagree about which part of the building this is, and averaging
         * them would invent a position between two real ones.
         */
        const val MAX_NEIGHBOR_SPREAD_METERS = 10.0

        /** Successive agreeing offsets required before the lock is taken. */
        const val OFFSETS_TO_CONFIRM = 4

        /** How closely those offsets must agree, in meters. */
        const val OFFSET_AGREEMENT_METERS = 4.0

        /** Minimum walking between offset estimates, so they are independent. */
        const val MIN_SPACING_METERS = 1.5

        private const val MISSING_SIGNAL_PENALTY_DB = 15.0
        private const val RSSI_WEIGHT = 1.0
        private const val MAGNETIC_WEIGHT = 0.3
    }

    private class Candidate(val east: Double, val north: Double)

    private val agreeing = mutableListOf<Candidate>()
    private var lastEstimateAtPathLength = Double.NEGATIVE_INFINITY

    val hasMap: Boolean get() = waypoints.isNotEmpty()

    fun reset() {
        agreeing.clear()
        lastEstimateAtPathLength = Double.NEGATIVE_INFINITY
    }

    /**
     * Offers a live signal snapshot taken at the given session position.
     * Returns the offset onto the stored map once enough successive estimates
     * agree, and null until then.
     */
    fun observe(
        wifiRssi: Map<String, Int>,
        bleRssi: Map<String, Int>,
        magneticMagnitudeUt: Float?,
        sessionEast: Double,
        sessionNorth: Double,
        pathLengthMeters: Double,
    ): Relocalization? {
        if (waypoints.isEmpty()) return null
        if (pathLengthMeters - lastEstimateAtPathLength < MIN_SPACING_METERS) return null
        lastEstimateAtPathLength = pathLengthMeters

        val fix = estimateMapPosition(wifiRssi, bleRssi, magneticMagnitudeUt)
        if (fix == null) {
            // A bad look at the building is not evidence against what we have
            // already seen, but a run of them means we have moved somewhere
            // unrecognised, so let the run decay rather than persist forever.
            if (agreeing.isNotEmpty()) agreeing.removeAt(0)
            return null
        }

        val candidate = Candidate(fix.first - sessionEast, fix.second - sessionNorth)
        agreeing.add(candidate)
        if (agreeing.size > OFFSETS_TO_CONFIRM) agreeing.removeAt(0)
        if (agreeing.size < OFFSETS_TO_CONFIRM) return null

        val east = agreeing.map { it.east }
        val north = agreeing.map { it.north }
        val spread = maxOf(
            (east.maxOrNull() ?: 0.0) - (east.minOrNull() ?: 0.0),
            (north.maxOrNull() ?: 0.0) - (north.minOrNull() ?: 0.0),
        )
        if (spread > OFFSET_AGREEMENT_METERS) return null

        return Relocalization(
            offsetEastMeters = median(east),
            offsetNorthMeters = median(north),
            uncertaintyMeters = spread / 2,
        )
    }

    /** Weighted k-nearest-neighbour vote on where in the map these signals were taken. */
    private fun estimateMapPosition(
        wifiRssi: Map<String, Int>,
        bleRssi: Map<String, Int>,
        magneticMagnitudeUt: Float?,
    ): Pair<Double, Double>? {
        if (wifiRssi.isEmpty() && bleRssi.isEmpty()) return null

        val scored = waypoints.map { it to distanceTo(it, wifiRssi, bleRssi, magneticMagnitudeUt) }
        val nearest = scored.sortedBy { it.second }.take(K_NEIGHBORS)
        val best = nearest.firstOrNull() ?: return null
        if (best.second > MAX_MATCH_DISTANCE) return null

        val typical = median(scored.map { it.second })
        if (typical <= 0.0 || best.second > DISTINCTIVENESS_RATIO * typical) return null

        // Voters that disagree about which part of the building this is would
        // average out to a place nobody voted for.
        val spread = nearest.maxOf { (waypoint, _) ->
            hypot(waypoint.xMeters - best.first.xMeters, waypoint.yMeters - best.first.yMeters)
        }
        if (spread > MAX_NEIGHBOR_SPREAD_METERS) return null

        var weightSum = 0.0
        var east = 0.0
        var north = 0.0
        nearest.forEach { (waypoint, distance) ->
            val weight = 1.0 / (distance + 0.5)
            weightSum += weight
            east += weight * waypoint.xMeters
            north += weight * waypoint.yMeters
        }
        if (weightSum <= 0.0) return null
        return east / weightSum to north / weightSum
    }

    private fun distanceTo(
        waypoint: MapWaypoint,
        wifiRssi: Map<String, Int>,
        bleRssi: Map<String, Int>,
        magneticMagnitudeUt: Float?,
    ): Double {
        var weighted = 0.0
        var totalWeight = 0.0
        rssiDistance(wifiRssi, waypoint.wifiRssi)?.let {
            weighted += RSSI_WEIGHT * it
            totalWeight += RSSI_WEIGHT
        }
        rssiDistance(bleRssi, waypoint.bleRssi)?.let {
            weighted += RSSI_WEIGHT * it
            totalWeight += RSSI_WEIGHT
        }
        val storedMagnetic = waypoint.magneticMagnitudeUt
        if (magneticMagnitudeUt != null && storedMagnetic != null) {
            weighted += MAGNETIC_WEIGHT * abs(magneticMagnitudeUt - storedMagnetic)
            totalWeight += MAGNETIC_WEIGHT
        }
        return if (totalWeight == 0.0) Double.MAX_VALUE else weighted / totalWeight
    }

    private fun rssiDistance(live: Map<String, Int>, stored: Map<String, Int>): Double? {
        val keys = live.keys + stored.keys
        if (keys.isEmpty()) return null
        val sumSquares = keys.sumOf { key ->
            val a = live[key]
            val b = stored[key]
            val difference =
                if (a != null && b != null) (a - b).toDouble() else MISSING_SIGNAL_PENALTY_DB
            difference * difference
        }
        return sqrt(sumSquares / keys.size)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
