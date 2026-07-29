package com.wesrable.positioning.vision

import kotlin.math.abs
import kotlin.math.atan2

/** One interesting point in an image, with the orientation of its surroundings. */
data class Keypoint(
    val x: Int,
    val y: Int,
    /** Corner strength, used to keep the best when there are too many. */
    val score: Int,
    /** Dominant direction of the local patch, in radians. */
    val angleRadians: Double,
)

/**
 * FAST corner detection — finds the points in an image that are distinctive
 * enough to recognise again from a different angle.
 *
 * This is the first half of recognising a place without putting anything in it.
 * A printed marker is easy because it *is* a pattern; a cabinet has to be
 * identified by whatever happens to be around it, which means finding parts of
 * the scene that look the same from a slightly different viewpoint. Corners
 * qualify and edges do not: slide along an edge and it looks identical, so it
 * cannot pin down where you were standing, while a corner shifts distinctly.
 *
 * The test is deliberately crude and therefore fast: a pixel is a corner when a
 * long enough arc of the sixteen pixels around it is all clearly brighter, or
 * all clearly darker, than it is. No gradients, no floating point.
 */
object FastCorners {

    /**
     * How much brighter or darker a ring pixel must be to count. Low values
     * find corners in flat, evenly-lit walls — which is where a room short of
     * texture most needs them — at the cost of also finding sensor noise.
     */
    const val THRESHOLD = 20

    /** Contiguous ring pixels required. Nine is the standard FAST-9 setting. */
    const val ARC_LENGTH = 9

    /** Radius of the patch whose brightness decides a keypoint's orientation. */
    const val ORIENTATION_RADIUS = 15

    /**
     * Keypoints are useless crowded together — a hundred on one door handle
     * describe the handle, not the room — so the image is divided into a grid
     * and the best are kept from each cell. This spreads them over the whole
     * frame, which is what makes the match depend on the scene rather than on
     * one object in it.
     */
    const val GRID_COLUMNS = 8
    const val GRID_ROWS = 6
    const val PER_CELL = 8

    /** Minimum pixels between two kept corners. See [suppressNeighbours]. */
    const val MIN_SEPARATION = 6

    private val CIRCLE_X = intArrayOf(0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1)
    private val CIRCLE_Y = intArrayOf(-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3)

    fun detect(image: GrayImage, threshold: Int = THRESHOLD): List<Keypoint> {
        // The orientation patch must fit, and it is the larger of the two
        // windows, so it sets the border.
        val margin = ORIENTATION_RADIUS + 1
        if (image.width <= 2 * margin || image.height <= 2 * margin) return emptyList()

        val found = mutableListOf<Keypoint>()
        for (y in margin until image.height - margin) {
            for (x in margin until image.width - margin) {
                val score = cornerScore(image, x, y, threshold) ?: continue
                found.add(Keypoint(x, y, score, orientationAt(image, x, y)))
            }
        }
        return spreadOverGrid(suppressNeighbours(found), image.width, image.height)
    }

    /**
     * Corner strength, or null if this pixel is not a corner.
     *
     * The four compass points are tested first, as a cheap way to reject most
     * of an image after four comparisons instead of sixteen. The bar is *two*
     * of the four, not three: the compass points sit four apart on the ring, so
     * an arc of nine consecutive is only guaranteed to contain two of them.
     *
     * Three of four is the rule for FAST-12, and using it here rejected every
     * corner in the image — including a plain dark square on a light
     * background, where a 90-degree corner puts exactly two compass points on
     * the far side of the threshold. The detector returned zero keypoints at
     * every threshold until this was traced.
     */
    private fun cornerScore(image: GrayImage, x: Int, y: Int, threshold: Int): Int? {
        val center = image[x, y]
        val bright = center + threshold
        val dark = center - threshold

        var brightCount = 0
        var darkCount = 0
        for (index in intArrayOf(0, 4, 8, 12)) {
            val value = image[x + CIRCLE_X[index], y + CIRCLE_Y[index]]
            if (value > bright) brightCount++ else if (value < dark) darkCount++
        }
        if (brightCount < 2 && darkCount < 2) return null

        val ring = IntArray(16) { image[x + CIRCLE_X[it], y + CIRCLE_Y[it]] }
        if (!hasArc(ring, bright, brighter = true) && !hasArc(ring, dark, brighter = false)) {
            return null
        }
        return ring.sumOf { abs(it - center) }
    }

    /** Whether [ARC_LENGTH] consecutive ring pixels lie past the threshold. */
    private fun hasArc(ring: IntArray, threshold: Int, brighter: Boolean): Boolean {
        var run = 0
        // The ring wraps, so a run can straddle the start; walking one and a
        // half times round catches those without special-casing.
        for (step in 0 until 16 + ARC_LENGTH) {
            val value = ring[step % 16]
            val past = if (brighter) value > threshold else value < threshold
            if (past) {
                run++
                if (run >= ARC_LENGTH) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /**
     * Orientation from the intensity centroid: the direction from the patch's
     * centre to its centre of brightness.
     *
     * Without this the descriptor would only match images held at the same
     * angle, and a phone is never held twice at quite the same angle. Rotating
     * the sampling pattern by this angle instead makes the descriptor depend on
     * the scene rather than on how the wrist happened to be turned.
     */
    private fun orientationAt(image: GrayImage, x: Int, y: Int): Double {
        var momentX = 0L
        var momentY = 0L
        val radiusSquared = ORIENTATION_RADIUS * ORIENTATION_RADIUS
        for (dy in -ORIENTATION_RADIUS..ORIENTATION_RADIUS) {
            for (dx in -ORIENTATION_RADIUS..ORIENTATION_RADIUS) {
                if (dx * dx + dy * dy > radiusSquared) continue
                val value = image[x + dx, y + dy]
                momentX += dx.toLong() * value
                momentY += dy.toLong() * value
            }
        }
        return atan2(momentY.toDouble(), momentX.toDouble())
    }

    /**
     * Keeps only the strongest corner within [MIN_SEPARATION] pixels of any
     * other, greedily from the strongest down.
     *
     * Without this every corner in the image contributes a small cluster of
     * near-identical keypoints, and that quietly destroys matching rather than
     * merely wasting time: two keypoints a pixel apart have nearly identical
     * descriptors, so a query point's best and second-best match are the same
     * physical corner seen twice. The ratio test then sees no clear winner and
     * discards the match — meaning duplicates suppress the very features they
     * duplicate.
     */
    private fun suppressNeighbours(keypoints: List<Keypoint>): List<Keypoint> {
        val kept = mutableListOf<Keypoint>()
        val separationSquared = MIN_SEPARATION * MIN_SEPARATION
        keypoints.sortedByDescending { it.score }.forEach { candidate ->
            val crowded = kept.any { existing ->
                val dx = existing.x - candidate.x
                val dy = existing.y - candidate.y
                dx * dx + dy * dy < separationSquared
            }
            if (!crowded) kept.add(candidate)
        }
        return kept
    }

    /** Keeps the strongest few per grid cell, so coverage beats raw strength. */
    private fun spreadOverGrid(
        keypoints: List<Keypoint>,
        width: Int,
        height: Int,
    ): List<Keypoint> {
        if (keypoints.isEmpty()) return emptyList()
        val cellWidth = (width + GRID_COLUMNS - 1) / GRID_COLUMNS
        val cellHeight = (height + GRID_ROWS - 1) / GRID_ROWS
        val cells = HashMap<Int, MutableList<Keypoint>>()

        keypoints.forEach { point ->
            val cell = (point.y / cellHeight) * GRID_COLUMNS + (point.x / cellWidth)
            cells.getOrPut(cell) { mutableListOf() }.add(point)
        }

        return cells.values.flatMap { inCell ->
            inCell.sortedByDescending { it.score }.take(PER_CELL)
        }
    }
}
