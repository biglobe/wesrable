package com.wesrable.positioning.fingerprint

import com.wesrable.positioning.model.ResolutionBin
import com.wesrable.positioning.model.SurveyPoint
import com.wesrable.positioning.model.SurveyReport
import com.wesrable.positioning.model.SurveyReportStatus
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Measures how finely *this particular building* can be located from the
 * signals it actually has, instead of quoting a figure from a paper written
 * about a different one.
 *
 * The method is leave-one-out cross-validation, which is the standard way to
 * score a fingerprint map: hold each sample out, locate it using only the
 * others, and see how far off the answer was. Alongside that it reports a
 * resolution curve — of the sample pairs that were a metre apart, what
 * fraction looked different enough to tell apart, and the same at half a
 * metre, two metres, four.
 *
 * Three details separate an honest number here from a flattering one:
 *
 *  - **Pairs recorded close together in time are never compared.** Two samples
 *    taken four seconds apart share more than a location: they share the same
 *    bodies in the same doorways, the same interference, the same everything.
 *    Scoring against those measures how repeatable one walk is, which is not
 *    the question. Only pairs at least [MIN_SEPARATION_MILLIS] apart count, so
 *    every comparison is between separate passes through the same place.
 *
 *  - **Identical WiFi readings are discarded rather than believed.** Android
 *    throttles WiFi scans to roughly one per 30 s, so consecutive survey
 *    samples routinely carry byte-identical RSSI maps. Treating that as
 *    evidence would be measuring the scan throttle.
 *
 *  - **The threshold for "distinguished" is derived from the data**, not
 *    picked. It is the 90th percentile of the signal distance between samples
 *    taken at effectively the same spot on different passes — the size of the
 *    difference that noise alone produces here. That fixes the false-alarm
 *    rate at about 10% by construction, so a separation band scoring near 10%
 *    is telling you it is indistinguishable from standing still.
 *
 * Physical separations come from dead reckoning, which drifts. That matters
 * far less than it sounds: drift accumulates over a walk, while every
 * comparison here is between two points a few metres apart, over which the
 * relative error is small. The bands the whole exercise turns on — half a
 * metre, one metre — are exactly the ones dead reckoning gets right.
 */
object FingerprintCrossValidation {

    /** Below this there are not enough pairs for any figure to mean anything. */
    const val MIN_POINTS = 20

    /** Two samples this close together are treated as the same place. */
    const val NEAR_REPEAT_METERS = 0.4

    /**
     * Samples closer together in time than this are assumed to be from the
     * same pass and are never compared. Fifteen seconds is roughly a lap of a
     * small room at walking pace.
     */
    const val MIN_SEPARATION_MILLIS = 15_000L

    /** Same-place pairs allowed to exceed the threshold: the false-alarm rate. */
    const val NOISE_FLOOR_PERCENTILE = 0.9

    /** Fraction a band must reach to count as reliably resolved. */
    const val RESOLVED_FRACTION = 0.9

    /** Neighbours voting on the held-out sample's position. */
    const val K_NEIGHBORS = 3

    /** Fewer same-place pairs than this and the noise floor is a guess. */
    const val MIN_REVISIT_PAIRS = 8

    /** A band with fewer pairs than this is reported but not trusted. */
    const val MIN_BIN_PAIRS = 10

    private val BIN_EDGES =
        listOf(0.0, 0.5, 1.0, 2.0, 4.0, 8.0, Double.POSITIVE_INFINITY)

    fun report(points: List<SurveyPoint>): SurveyReport {
        if (points.size < MIN_POINTS) {
            return SurveyReport(
                status = SurveyReportStatus.NOT_ENOUGH_POINTS,
                pointCount = points.size,
                spanMeters = spanOf(points),
            )
        }

        // Pass one: the noise floor. Only same-place, different-pass pairs,
        // which is a small enough set to hold in memory outright.
        val revisitDistances = mutableListOf<Double>()
        forEachComparablePair(points) { a, b, separation, signal ->
            if (separation <= NEAR_REPEAT_METERS) revisitDistances.add(signal)
        }

        if (revisitDistances.size < MIN_REVISIT_PAIRS) {
            return SurveyReport(
                status = SurveyReportStatus.NOT_ENOUGH_REVISITS,
                pointCount = points.size,
                revisitPairCount = revisitDistances.size,
                spanMeters = spanOf(points),
            )
        }

        val noiseFloor = percentile(revisitDistances, NOISE_FLOOR_PERCENTILE)

        // Pass two: bin every pair by separation, and at the same time keep
        // each sample's nearest neighbours for the leave-one-out fix. Done
        // together because the pair list itself is far too large to keep —
        // 1200 samples is over 700,000 pairs.
        val binPairCounts = IntArray(BIN_EDGES.size - 1)
        val binDistinguished = IntArray(BIN_EDGES.size - 1)
        val neighbors = List(points.size) { TopK(K_NEIGHBORS) }
        var comparedPairs = 0

        forEachComparablePair(points) { a, b, separation, signal ->
            comparedPairs++
            val bin = binOf(separation)
            binPairCounts[bin]++
            if (signal > noiseFloor) binDistinguished[bin]++
            neighbors[a].offer(b, signal)
            neighbors[b].offer(a, signal)
        }

        val bins = (0 until BIN_EDGES.size - 1).map { index ->
            ResolutionBin(
                lowMeters = BIN_EDGES[index],
                highMeters = BIN_EDGES[index + 1],
                pairCount = binPairCounts[index],
                distinguishedFraction = if (binPairCounts[index] == 0) {
                    0.0
                } else {
                    binDistinguished[index].toDouble() / binPairCounts[index]
                },
            )
        }

        val errors = points.indices.mapNotNull { index ->
            locate(points, neighbors[index])?.let { (east, north) ->
                hypot(east - points[index].xMeters, north - points[index].yMeters)
            }
        }.sorted()

        return SurveyReport(
            status = SurveyReportStatus.READY,
            pointCount = points.size,
            comparedPairCount = comparedPairs,
            revisitPairCount = revisitDistances.size,
            noiseFloorDb = noiseFloor,
            medianErrorMeters = errors.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) },
            p90ErrorMeters = errors.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.9) },
            bins = bins,
            resolvedAtMeters = resolvedAt(bins),
            spanMeters = spanOf(points),
        )
    }

    /**
     * Every pair worth comparing, with its physical separation and its signal
     * distance. Streamed to [action] rather than returned, because at the
     * survey cap the list would run to hundreds of thousands of entries.
     */
    private inline fun forEachComparablePair(
        points: List<SurveyPoint>,
        action: (indexA: Int, indexB: Int, separationMeters: Double, signalDistance: Double) -> Unit,
    ) {
        for (i in points.indices) {
            val a = points[i]
            for (j in i + 1 until points.size) {
                val b = points[j]
                if (abs(a.recordedAtMillis - b.recordedAtMillis) < MIN_SEPARATION_MILLIS) continue
                val staleWifi = a.wifiRssi.isNotEmpty() && a.wifiRssi == b.wifiRssi
                val signal = FingerprintMatcher.signalDistance(
                    wifiA = a.wifiRssi,
                    bleA = a.bleRssi,
                    magneticA = a.magneticMagnitudeUt,
                    wifiB = b.wifiRssi,
                    bleB = b.bleRssi,
                    magneticB = b.magneticMagnitudeUt,
                    skipWifi = staleWifi,
                ) ?: continue
                action(i, j, hypot(a.xMeters - b.xMeters, a.yMeters - b.yMeters), signal)
            }
        }
    }

    /** Signal-distance-weighted centroid of the neighbours — the app's own fix. */
    private fun locate(points: List<SurveyPoint>, neighbors: TopK): Pair<Double, Double>? {
        if (neighbors.size == 0) return null
        var east = 0.0
        var north = 0.0
        var weight = 0.0
        for (slot in 0 until neighbors.size) {
            val point = points[neighbors.indexAt(slot)]
            val w = 1.0 / (neighbors.distanceAt(slot) + 1.0)
            east += point.xMeters * w
            north += point.yMeters * w
            weight += w
        }
        if (weight <= 0.0) return null
        return east / weight to north / weight
    }

    /**
     * Narrowest band that holds up, walking inwards from the widest. A band
     * only counts once every wider band has already passed, so one lucky tight
     * band cannot claim a resolution the coarser evidence contradicts.
     *
     * A band with too few pairs is skipped while walking in, and only becomes
     * a stopping point once counting has started. The difference matters in a
     * small flat, where nothing is ever 8 m apart: an empty widest band is
     * absent evidence, and treating it as contrary evidence would report that
     * a home resolves nothing purely for being small.
     */
    private fun resolvedAt(bins: List<ResolutionBin>): Double? {
        var resolved: Double? = null
        var counting = false
        for (bin in bins.reversed()) {
            if (bin.pairCount < MIN_BIN_PAIRS) {
                if (counting) break else continue
            }
            counting = true
            if (bin.distinguishedFraction < RESOLVED_FRACTION) break
            resolved = bin.lowMeters
        }
        return resolved
    }

    private fun binOf(separationMeters: Double): Int {
        for (index in 0 until BIN_EDGES.size - 1) {
            if (separationMeters < BIN_EDGES[index + 1]) return index
        }
        return BIN_EDGES.size - 2
    }

    private fun spanOf(points: List<SurveyPoint>): Double {
        if (points.isEmpty()) return 0.0
        val minX = points.minOf { it.xMeters }
        val maxX = points.maxOf { it.xMeters }
        val minY = points.minOf { it.yMeters }
        val maxY = points.maxOf { it.yMeters }
        return hypot(maxX - minX, maxY - minY)
    }

    /** Nearest-rank percentile over an unsorted list. */
    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * The k smallest signal distances seen for one sample, kept as parallel
     * primitive arrays. k is 3, so a linear insert beats a heap, and holding
     * one of these per sample costs a few dozen bytes rather than the
     * hundreds of megabytes a full pair list would.
     */
    private class TopK(private val capacity: Int) {
        private val indices = IntArray(capacity)
        private val distances = DoubleArray(capacity)
        var size = 0
            private set

        fun offer(index: Int, distance: Double) {
            if (size == capacity && distance >= distances[size - 1]) return
            var slot = if (size < capacity) size++ else capacity - 1
            while (slot > 0 && distances[slot - 1] > distance) {
                distances[slot] = distances[slot - 1]
                indices[slot] = indices[slot - 1]
                slot--
            }
            distances[slot] = distance
            indices[slot] = index
        }

        fun indexAt(slot: Int): Int = indices[slot]

        fun distanceAt(slot: Int): Double = distances[slot]
    }
}
