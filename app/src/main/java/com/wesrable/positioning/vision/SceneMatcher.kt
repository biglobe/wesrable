package com.wesrable.positioning.vision

import kotlin.math.abs

/**
 * Decides whether two views are of the same place.
 *
 * Descriptors are matched by counting differing bits, and a match is kept only
 * if it is clearly better than the runner-up — Lowe's ratio test. That second
 * condition does most of the work here, and it is what makes the technique
 * viable for identical cabinets at all: a featureless patch of white door
 * matches every other patch of white door about equally well, so its best match
 * is barely better than its second, and it is discarded. What survives are the
 * points that are distinctive in this room, which is exactly the set that tells
 * one cabinet's surroundings from its neighbour's.
 *
 * Surviving matches are then checked for agreeing with each other. Two views of
 * one place, taken a step apart, shift their features by broadly the same
 * amount and direction; a coincidental pile of matches between two unrelated
 * views does not. Requiring agreement removes the false matches that ratio
 * testing alone leaves behind.
 */
object SceneMatcher {

    /**
     * A match is kept when the best candidate is this much closer than the
     * second best. Loosening it admits repeated texture — the very thing that
     * makes identical furniture hard — so it stays strict.
     */
    const val RATIO = 0.75

    /** Beyond this many differing bits out of 256, two points are unrelated. */
    const val MAX_HAMMING = 64

    /**
     * How far a match's displacement may sit from the median displacement and
     * still count as agreeing, as a fraction of image width. Generous enough
     * for the parallax a real viewpoint change produces, tight enough that
     * unrelated matches scattered at random do not all pass.
     */
    const val AGREEMENT_FRACTION = 0.12

    /** Below this many agreeing matches, a claim of "same place" is noise. */
    const val MIN_AGREEING = 15

    /**
     * Agreeing matches as a share of the smaller signature. An absolute count
     * alone is not a threshold at all: a cluttered view yields hundreds of
     * keypoints and clears any fixed bar by accident, while a plain wall yields
     * thirty and cannot clear it however right it is. Measured against a
     * different room, a fixed count of 12 accepted half of them.
     */
    const val MIN_INLIER_RATIO = 0.18

    /** How alike two views are, and on what evidence. */
    data class Result(
        /** Matches surviving the ratio test. */
        val matches: Int,
        /** Of those, how many displace consistently with the rest. */
        val agreeing: Int,
        /**
         * Keypoints in the *query*, not in the smaller of the two.
         *
         * Normalising by the smaller signature looks fairer and ranks
         * catastrophically: a reference view of a blank wall has few keypoints,
         * so a handful of chance agreements gives it a high share, and it beats
         * the correct view whose larger keypoint set dilutes a far better
         * absolute result. Every candidate in one query must be divided by the
         * same number for the comparison between them to mean anything.
         */
        val comparable: Int,
    ) {
        /**
         * The share of the query's keypoints that agree. A ratio rather than a
         * raw count, since a cluttered view produces more matches against
         * everything and a raw count would elect the busiest corner of the
         * house as the answer to every query.
         */
        val score: Double
            get() = if (comparable == 0) 0.0 else agreeing.toDouble() / comparable

        val isSamePlace: Boolean
            get() = agreeing >= MIN_AGREEING && score >= MIN_INLIER_RATIO
    }

    fun compare(query: SceneSignature, reference: SceneSignature): Result {
        if (query.size == 0 || reference.size == 0) return Result(0, 0, query.size)
        val comparable = query.size

        val dx = ArrayList<Int>(query.size)
        val dy = ArrayList<Int>(query.size)

        for (queryIndex in 0 until query.size) {
            var bestDistance = Int.MAX_VALUE
            var secondDistance = Int.MAX_VALUE
            var bestIndex = -1

            for (referenceIndex in 0 until reference.size) {
                val distance = hamming(query, queryIndex, reference, referenceIndex)
                if (distance < bestDistance) {
                    secondDistance = bestDistance
                    bestDistance = distance
                    bestIndex = referenceIndex
                } else if (distance < secondDistance) {
                    secondDistance = distance
                }
            }

            if (bestIndex < 0 || bestDistance > MAX_HAMMING) continue
            // A featureless patch matches everything about equally, so its best
            // is barely better than its second; that is the case to drop.
            if (bestDistance >= RATIO * secondDistance) continue

            dx.add(query.xs[queryIndex] - reference.xs[bestIndex])
            dy.add(query.ys[queryIndex] - reference.ys[bestIndex])
        }

        if (dx.isEmpty()) return Result(0, 0, comparable)

        // Median rather than mean: a handful of wild false matches would drag a
        // mean far enough to let the rest of the false matches agree with it.
        val medianX = median(dx)
        val medianY = median(dy)
        val tolerance = query.width * AGREEMENT_FRACTION

        var agreeing = 0
        for (index in dx.indices) {
            if (abs(dx[index] - medianX) <= tolerance && abs(dy[index] - medianY) <= tolerance) {
                agreeing++
            }
        }

        return Result(matches = dx.size, agreeing = agreeing, comparable = comparable)
    }

    private fun hamming(
        a: SceneSignature,
        aIndex: Int,
        b: SceneSignature,
        bIndex: Int,
    ): Int {
        var total = 0
        for (word in 0 until BriefDescriptor.WORDS) {
            total += java.lang.Long.bitCount(
                a.descriptorWord(aIndex, word) xor b.descriptorWord(bIndex, word)
            )
        }
        return total
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
