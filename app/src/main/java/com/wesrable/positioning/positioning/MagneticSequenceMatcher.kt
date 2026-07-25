package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.LoopClosure
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Recognises a revisited stretch of floor by the *shape* of the magnetic
 * field along it, which is far sharper than anything RSSI can offer.
 *
 * Steel and wiring impose structure on a building's magnetic field from tens
 * of centimetres upward. A single reading is nearly useless for placing
 * yourself — measured in simulation, standing still and standing a quarter of
 * a metre away give overlapping values — but the *sequence* of readings along
 * a walked path is a signature. Comparing a window of recent samples against
 * everywhere already walked resolves position to well under a quarter of a
 * metre in a structured building, against the 4-8 m that RSSI fingerprinting
 * manages. That difference is the whole point: it is what lets a loop close
 * in a room, where drift is a metre or two and RSSI cannot see an error that
 * small.
 *
 * Samples are indexed by *distance walked*, not by time, so the signature
 * does not change with walking speed. They are recorded no finer than a
 * stride, since that is how often the app learns where it is — which turns
 * out not to matter, as most of the field's energy sits at wavelengths a
 * stride easily resolves.
 *
 * No Android dependencies, so it can be exercised on the JVM.
 */
class MagneticSequenceMatcher {

    companion object {
        /** Roughly a stride: the finest the app can place a reading. */
        const val SAMPLE_SPACING_METERS = 0.6

        /**
         * Samples per comparison window — about 6 m of walking. Shorter
         * windows are less distinctive, longer ones need more of the path
         * retraced before they can match, which a small home cannot spare.
         */
        const val WINDOW_SAMPLES = 10

        /** Attempt a match every this many samples, to bound the work done. */
        const val MATCH_EVERY_SAMPLES = 4

        /**
         * RMS difference, in microtesla, below which two stretches are the
         * same floor. Standing still measures ~0.5 even with sensor noise,
         * while a quarter of a metre away already measures ~1.7.
         */
        const val MATCH_THRESHOLD_UT = 1.0

        /**
         * The best match must beat the best *elsewhere* by this factor.
         *
         * Without it the technique fails badly in a magnetically bland
         * building — timber framed, little steel — where the field varies so
         * smoothly that a stretch 15 m away still scores 0.86 and would be
         * accepted outright. There the correct behaviour is to decline, and
         * requiring the winner to be distinctly better than the runner-up is
         * what produces that: where everything matches equally well, nothing
         * is distinctive enough to act on.
         */
        const val DISTINCTIVENESS_RATIO = 0.5

        /** Enough separation that the window cannot overlap its own match. */
        const val MIN_SEPARATION_METERS = 10.0

        /**
         * Smallest correction worth applying. Far below the 5 m the RSSI path
         * needs, because the constraint is that much sharper — this is what
         * makes a room-sized loop correctable at all.
         */
        const val MIN_CORRECTION_METERS = 0.5

        /** ~2 km of walking; beyond this new samples are dropped, not rotated. */
        const val MAX_SAMPLES = 3500
    }

    private class Sample(
        var x: Double,
        var y: Double,
        val pathLength: Double,
        val magnitudeUt: Float,
    )

    private val samples = mutableListOf<Sample>()
    private var lastSampleAtPathLength = Double.NEGATIVE_INFINITY
    private var samplesSinceMatch = 0

    fun reset() {
        samples.clear()
        lastSampleAtPathLength = Double.NEGATIVE_INFINITY
        samplesSinceMatch = 0
    }

    /**
     * Offers a magnetic reading taken at the given dead-reckoned position.
     * Returns a closure when the recent stretch of path is recognised as one
     * already walked.
     */
    fun observe(
        magnitudeUt: Float?,
        xMeters: Double,
        yMeters: Double,
        pathLengthMeters: Double,
    ): LoopClosure? {
        if (magnitudeUt == null) return null
        if (pathLengthMeters - lastSampleAtPathLength < SAMPLE_SPACING_METERS) return null
        lastSampleAtPathLength = pathLengthMeters

        if (samples.size >= MAX_SAMPLES) return null
        samples.add(Sample(xMeters, yMeters, pathLengthMeters, magnitudeUt))

        samplesSinceMatch++
        if (samplesSinceMatch < MATCH_EVERY_SAMPLES) return null
        samplesSinceMatch = 0

        return findRevisit(pathLengthMeters)
    }

    /** Keeps stored positions in step with a trail the closure has rewritten. */
    fun applyCorrection(
        anchorPathLength: Double,
        endPathLength: Double,
        driftEast: Double,
        driftNorth: Double,
    ) {
        val span = endPathLength - anchorPathLength
        if (span <= 0.0) return
        samples.forEach { sample ->
            val fraction = when {
                sample.pathLength <= anchorPathLength -> 0.0
                sample.pathLength >= endPathLength -> 1.0
                else -> (sample.pathLength - anchorPathLength) / span
            }
            if (fraction == 0.0) return@forEach
            sample.x -= driftEast * fraction
            sample.y -= driftNorth * fraction
        }
    }

    /** Slides every sample bodily, for a rebase onto a stored map. */
    fun translate(east: Double, north: Double) {
        samples.forEach {
            it.x += east
            it.y += north
        }
    }

    private fun findRevisit(currentPathLength: Double): LoopClosure? {
        if (samples.size < WINDOW_SAMPLES * 2) return null

        val windowStart = samples.size - WINDOW_SAMPLES
        val window = DoubleArray(WINDOW_SAMPLES) { samples[windowStart + it].magnitudeUt.toDouble() }
        val windowMean = window.average()

        var bestEnd = -1
        var bestDistance = Double.MAX_VALUE
        val scored = mutableListOf<Pair<Int, Double>>()

        for (end in WINDOW_SAMPLES - 1 until windowStart) {
            if (currentPathLength - samples[end].pathLength < MIN_SEPARATION_METERS) break
            val distance = compare(window, windowMean, end)
            scored.add(end to distance)
            if (distance < bestDistance) {
                bestDistance = distance
                bestEnd = end
            }
        }

        if (bestEnd < 0 || bestDistance > MATCH_THRESHOLD_UT) return null

        // The winner has to stand out against the run of the candidates, or
        // the field here is too bland to be telling us anything. Compared
        // against the median rather than the runner-up: walking a circuit
        // repeatedly makes the same patch of floor match at several points in
        // the path, all of them correct, so the runner-up is usually another
        // genuine match rather than evidence of ambiguity. Those correct
        // matches are always a small minority of all candidates, so they
        // leave the median alone.
        val typical = medianOf(scored.map { it.second })
        if (typical <= 0.0 || bestDistance > DISTINCTIVENESS_RATIO * typical) return null

        val winner = samples[bestEnd]

        val current = samples.last()
        val driftEast = current.x - winner.x
        val driftNorth = current.y - winner.y
        if (hypot(driftEast, driftNorth) < MIN_CORRECTION_METERS) return null

        return LoopClosure(
            matchedIndex = bestEnd,
            anchorPathLengthMeters = winner.pathLength,
            currentPathLengthMeters = current.pathLength,
            driftEastMeters = driftEast,
            driftNorthMeters = driftNorth,
        )
    }

    private fun medianOf(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * RMS difference between the live window and the stretch ending at [end],
     * each with its own mean removed. Comparing shape rather than level keeps
     * the match alive across a mid-session magnetometer recalibration, which
     * would otherwise shift every reading at once and break everything.
     */
    private fun compare(window: DoubleArray, windowMean: Double, end: Int): Double {
        val start = end - WINDOW_SAMPLES + 1
        var candidateMean = 0.0
        for (i in 0 until WINDOW_SAMPLES) candidateMean += samples[start + i].magnitudeUt
        candidateMean /= WINDOW_SAMPLES

        var sum = 0.0
        for (i in 0 until WINDOW_SAMPLES) {
            val difference =
                (window[i] - windowMean) - (samples[start + i].magnitudeUt - candidateMean)
            sum += difference * difference
        }
        return sqrt(sum / WINDOW_SAMPLES)
    }
}
