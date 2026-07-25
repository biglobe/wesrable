package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.RoomAnchor
import kotlin.math.hypot

/**
 * Pins the user's labeled rooms onto the trail map.
 *
 * Room fingerprints deliberately carry no coordinates — matching is pure
 * pattern comparison, which is what lets them be recorded by just standing
 * somewhere and naming it. That leaves them unplaceable on a map, though.
 * This closes the gap opportunistically: whenever a room matches while the
 * walker is somewhere, the dead-reckoned position of that moment is a
 * observation of where the room is, and the average over repeat sightings
 * settles onto the middle of wherever that room answers to.
 *
 * Individual sightings are kept rather than a running mean, because the trail
 * they are expressed against gets rewritten whenever a loop closes, and only
 * a sighting that remembers *when* along the path it happened can be
 * corrected along with it.
 *
 * No Android dependencies, so it can be exercised on the JVM.
 */
class RoomAnchorMap(
    private val minSpacingMeters: Double = MIN_SIGHTING_SPACING_METERS,
) {

    companion object {
        /**
         * Minimum distance walked between two sightings of the same room.
         * Without it the estimate is dominated by wherever the walker happened
         * to stand still longest, since matches arrive on every signal update
         * rather than every step.
         */
        const val MIN_SIGHTING_SPACING_METERS = 1.0

        /**
         * The winning room's minimum share of the k-nearest vote. A split
         * vote means the signals sit between two recorded rooms, which is
         * exactly when placing a label would be misleading.
         */
        const val MIN_CONFIDENCE = 0.5

        /**
         * How far, in signal distance, the closest stored sample may be.
         *
         * This matters more than the confidence does. k-NN always returns its
         * nearest neighbour, so standing in an unmapped garage still elects a
         * winner — possibly unanimously, since all three neighbours may be
         * the same distant room. Without this gate every unmapped corner of
         * the building would smear a confident, wrong label across the map.
         * Calibrated against the same measurements as loop closure, where a
         * distance of ~3 means standing still and ~6 means about 12 m away.
         */
        const val MAX_MATCH_DISTANCE = 6.0

        /**
         * How many times a room must be seen before its label is placed.
         *
         * Signal matching is noisy enough that an isolated wrong match is
         * routine — measured at a spot 17 m from any recorded room, the
         * nearest sample still scored inside [MAX_MATCH_DISTANCE]. Waiting
         * for corroboration costs a few meters of walking and stops one-off
         * mistakes from ever being drawn.
         */
        const val MIN_SIGHTINGS_TO_SHOW = 3

        /**
         * Once a room is placed, how far a new sighting may be from that
         * position and still be believed.
         *
         * A room occupies a bounded patch of floor, so a match reported from
         * far outside it is a matching error, not news. Without this a long
         * spell somewhere unmapped — where every scan still elects some
         * nearest room — produces enough wrong sightings to outvote the real
         * ones and drag the label right off the room.
         */
        const val MAX_SIGHTING_SPREAD_METERS = 8.0

        /**
         * Caps memory on a long walk. Once reached, further sightings are
         * ignored rather than replacing the oldest: a room does not move, so
         * the early sightings are not stale, and evicting them lets a later
         * bad patch take over a label that was already correct.
         */
        const val MAX_SIGHTINGS_PER_ROOM = 40
    }

    private class Sighting(var x: Double, var y: Double, val pathLength: Double)

    private val byLabel = linkedMapOf<String, MutableList<Sighting>>()
    private var lastSightingAtPathLength = Double.NEGATIVE_INFINITY

    /**
     * Every corroborated room, at its current best position.
     *
     * Positioned by per-axis median rather than mean. A room is placed from
     * matches that are individually unreliable, so the occasional sighting
     * lands somewhere the walker never was; a mean lets one such outlier drag
     * the label away in proportion to how far off it was, while a median
     * simply ignores it as long as most sightings are sound.
     */
    val anchors: List<RoomAnchor>
        get() = byLabel.mapNotNull { (label, sightings) ->
            if (sightings.size < MIN_SIGHTINGS_TO_SHOW) return@mapNotNull null
            RoomAnchor(
                label = label,
                xMeters = medianOf(sightings.map { it.x }),
                yMeters = medianOf(sightings.map { it.y }),
                sightings = sightings.size,
            )
        }

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    fun reset() {
        byLabel.clear()
        lastSightingAtPathLength = Double.NEGATIVE_INFINITY
    }

    /**
     * Offers a room match observed at the given position. Ignored unless the
     * match is trustworthy and the walker has moved since the last sighting.
     */
    fun note(
        label: String?,
        confidence: Double,
        nearestDistanceDb: Double,
        xMeters: Double,
        yMeters: Double,
        pathLengthMeters: Double,
    ) {
        if (label == null) return
        if (confidence < MIN_CONFIDENCE || nearestDistanceDb > MAX_MATCH_DISTANCE) return
        if (pathLengthMeters - lastSightingAtPathLength < minSpacingMeters) return
        lastSightingAtPathLength = pathLengthMeters

        val sightings = byLabel.getOrPut(label) { mutableListOf() }
        if (sightings.size >= MAX_SIGHTINGS_PER_ROOM) return

        // Once the room has a position, disbelieve matches reported from far
        // outside it rather than letting them move it.
        if (sightings.size >= MIN_SIGHTINGS_TO_SHOW) {
            val centreX = medianOf(sightings.map { it.x })
            val centreY = medianOf(sightings.map { it.y })
            if (hypot(xMeters - centreX, yMeters - centreY) > MAX_SIGHTING_SPREAD_METERS) return
        }

        sightings.add(Sighting(xMeters, yMeters, pathLengthMeters))
    }

    /**
     * Applies the same rubber-sheet correction the trail receives when a loop
     * closes, so labels keep pointing at the part of the trail they were
     * observed against instead of sliding out of alignment with it.
     */
    fun applyCorrection(
        anchorPathLength: Double,
        endPathLength: Double,
        driftEast: Double,
        driftNorth: Double,
    ) {
        val span = endPathLength - anchorPathLength
        if (span <= 0.0) return

        byLabel.values.forEach { sightings ->
            sightings.forEach { sighting ->
                val fraction = when {
                    sighting.pathLength <= anchorPathLength -> 0.0
                    sighting.pathLength >= endPathLength -> 1.0
                    else -> (sighting.pathLength - anchorPathLength) / span
                }
                if (fraction == 0.0) return@forEach
                sighting.x -= driftEast * fraction
                sighting.y -= driftNorth * fraction
            }
        }
    }
}
