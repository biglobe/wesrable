package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.SurveyPoint

/**
 * Collects a *dense* signal survey: one sample every half-metre of walking,
 * automatically, tagged with where the walker was standing.
 *
 * This exists to answer a question the app could previously only argue about.
 * Room fingerprints are recorded by hand, one per room, so they can say which
 * room you are in and nothing finer. Sampling every half-metre instead is the
 * same technique at a hundred times the density — and, more importantly,
 * produces enough pairs of nearby samples that the achievable resolution *in
 * this specific building* can be measured rather than assumed (see
 * `FingerprintCrossValidation`).
 *
 * Two lists are kept, and the distinction matters. [stored] came from earlier
 * sessions and is already expressed in the map's frame. [session] is this
 * walk, whose origin is wherever the phone happened to start; a relocalization
 * slides it into the map frame and a loop closure reshapes it, and neither of
 * those may touch the stored points, which were corrected when they were live
 * and are now settled.
 */
class SurveyTracker {

    private val stored = mutableListOf<SurveyPoint>()
    private val session = mutableListOf<SurveyPoint>()

    /** Everything available to measure against, old walks and this one. */
    val points: List<SurveyPoint> get() = stored + session

    /** Samples captured on this walk alone. */
    val sessionPointCount: Int get() = session.size

    /** Samples inherited from earlier walks, already in the map's frame. */
    val storedPoints: List<SurveyPoint> get() = stored.toList()

    fun load(points: List<SurveyPoint>) {
        stored.clear()
        stored.addAll(points.take(MAX_POINTS))
    }

    fun clear() {
        stored.clear()
        session.clear()
    }

    /**
     * Offers the current signal snapshot. Kept only when the walker has moved
     * [SPACING_METERS] since the last sample — sampling by distance rather
     * than by time is what keeps the survey even, and stops standing still
     * from burying the map under a hundred readings of one spot, which would
     * bias every statistic drawn from it towards that spot.
     */
    fun observe(
        xMeters: Double,
        yMeters: Double,
        pathLengthMeters: Double,
        wifiRssi: Map<String, Int>,
        bleRssi: Map<String, Int>,
        magneticMagnitudeUt: Float?,
        atMillis: Long,
    ) {
        if (session.size >= MAX_POINTS) return
        val last = session.lastOrNull()
        if (last != null && pathLengthMeters - last.pathLengthMeters < SPACING_METERS) return
        if (wifiRssi.isEmpty() && bleRssi.isEmpty() && magneticMagnitudeUt == null) return

        session.add(
            SurveyPoint(
                xMeters = xMeters,
                yMeters = yMeters,
                pathLengthMeters = pathLengthMeters,
                wifiRssi = wifiRssi,
                bleRssi = bleRssi,
                magneticMagnitudeUt = magneticMagnitudeUt,
                recordedAtMillis = atMillis,
            )
        )
    }

    /** Slides this session's samples into the stored map's frame. */
    fun translate(eastMeters: Double, northMeters: Double) {
        for (index in session.indices) {
            val point = session[index]
            session[index] = point.copy(
                xMeters = point.xMeters + eastMeters,
                yMeters = point.yMeters + northMeters,
            )
        }
    }

    /**
     * Applies a loop-closure correction, spread over the stretch of path it
     * was measured across, exactly as the trail itself is rubber-sheeted.
     * A sample recorded before the anchor is untouched; one recorded after the
     * closure takes the full shift; the ones in between are dragged
     * proportionally to how far along they were.
     */
    fun applyCorrection(
        anchorPathLengthMeters: Double,
        endPathLengthMeters: Double,
        eastMeters: Double,
        northMeters: Double,
    ) {
        val span = endPathLengthMeters - anchorPathLengthMeters
        if (span <= 0.0) return
        for (index in session.indices) {
            val point = session[index]
            if (point.pathLengthMeters <= anchorPathLengthMeters) continue
            val fraction =
                ((point.pathLengthMeters - anchorPathLengthMeters) / span).coerceAtMost(1.0)
            session[index] = point.copy(
                xMeters = point.xMeters - eastMeters * fraction,
                yMeters = point.yMeters - northMeters * fraction,
            )
        }
    }

    companion object {
        /**
         * Half a metre. Cabinets stand 30-60 cm apart, so anything coarser
         * could not even in principle produce a pair of samples separated by
         * the distance the whole exercise is about.
         */
        const val SPACING_METERS = 0.5

        /**
         * 1200 samples is 600 m of walking, which is a thorough survey of any
         * home. The cap matters because the analysis compares every pair:
         * cost grows with the square, so an uncapped survey would eventually
         * take longer to score than it took to walk.
         */
        const val MAX_POINTS = 1200
    }
}
