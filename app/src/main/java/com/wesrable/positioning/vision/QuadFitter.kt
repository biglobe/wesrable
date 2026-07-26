package com.wesrable.positioning.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A candidate marker outline: four corners in order around the shape. */
data class Quad(val xs: DoubleArray, val ys: DoubleArray) {
    val centerX: Double get() = xs.average()
    val centerY: Double get() = ys.average()

    /** Length of the shortest side, which caps how well the payload can be read. */
    val shortestSide: Double
        get() = (0 until 4).minOf { index ->
            val next = (index + 1) and 3
            hypot(xs[next] - xs[index], ys[next] - ys[index])
        }

    override fun equals(other: Any?): Boolean =
        other is Quad && xs.contentEquals(other.xs) && ys.contentEquals(other.ys)

    override fun hashCode(): Int = 31 * xs.contentHashCode() + ys.contentHashCode()
}

/**
 * Reduces traced outlines to four-cornered convex shapes, discarding everything
 * that cannot be a marker seen from some angle.
 *
 * This is where the great majority of candidates die, and that is the point. A
 * room is full of dark closed outlines — door frames, skirting, the shadow
 * under a shelf, the gap between two cabinets — and running the decoder on all
 * of them would be slow and would eventually produce a false marker id. Four
 * corners, convex, big enough to read, and not absurdly thin between them
 * removes nearly all of it for almost no cost.
 */
object QuadFitter {

    /**
     * Douglas-Peucker tolerance, as a fraction of the outline's length. Too
     * tight and camera noise along an edge becomes an extra corner; too loose
     * and a hexagon flattens into a quad. Five percent is the usual working
     * value for marker detection.
     */
    const val SIMPLIFY_FRACTION = 0.05

    /**
     * Smallest marker side, in pixels, worth trying to decode.
     *
     * Measured rather than guessed, by sweeping this value against apparent
     * marker size (see the README). The decoder still reads 100% correctly at
     * 16 px a side — under 3 pixels per cell — so an earlier setting of 20 was
     * discarding markers it could have read, costing about a third of the
     * usable range for nothing. Detection only collapses below 14 px.
     *
     * It is not set to 14, though. False positives on cluttered marker-free
     * frames first appear at 10, and naming the wrong cabinet is a far worse
     * failure here than failing to name one: the user can always step closer,
     * but has no way to notice a confident wrong answer. This keeps two steps
     * of margin from where clutter starts getting through, and still reaches
     * 1.5x further than the value it replaced.
     */
    const val MIN_SIDE_PIXELS = 14.0

    /**
     * Shortest side over longest. A square marker viewed at a glancing angle
     * genuinely does compress, so this has to stay permissive; it exists to
     * throw out slivers, not to insist on a head-on view.
     */
    const val MIN_SIDE_RATIO = 0.15

    fun fit(
        contours: List<ContourTracer.Contour>,
        minSidePixels: Double = MIN_SIDE_PIXELS,
    ): List<Quad> = contours.mapNotNull { fitOne(it, minSidePixels) }

    private fun fitOne(contour: ContourTracer.Contour, minSidePixels: Double): Quad? {
        val perimeter = perimeterOf(contour)
        val epsilon = perimeter * SIMPLIFY_FRACTION
        val corners = simplifyClosed(contour, epsilon)
        if (corners.size != 4) return null

        val xs = DoubleArray(4) { contour.xs[corners[it]].toDouble() }
        val ys = DoubleArray(4) { contour.ys[corners[it]].toDouble() }

        if (!isConvex(xs, ys)) return null

        var shortest = Double.MAX_VALUE
        var longest = 0.0
        for (index in 0 until 4) {
            val next = (index + 1) and 3
            val side = hypot(xs[next] - xs[index], ys[next] - ys[index])
            shortest = min(shortest, side)
            longest = max(longest, side)
        }
        if (shortest < minSidePixels) return null
        if (longest <= 0.0 || shortest / longest < MIN_SIDE_RATIO) return null

        // Normalise winding, so the decoder's rotation always means the same
        // thing regardless of which way round the tracer happened to walk.
        if (signedArea(xs, ys) < 0) {
            reverseInPlace(xs)
            reverseInPlace(ys)
        }
        return Quad(xs, ys)
    }

    private fun perimeterOf(contour: ContourTracer.Contour): Double {
        var total = 0.0
        for (index in 0 until contour.size) {
            val next = (index + 1) % contour.size
            total += hypot(
                (contour.xs[next] - contour.xs[index]).toDouble(),
                (contour.ys[next] - contour.ys[index]).toDouble(),
            )
        }
        return total
    }

    /**
     * Douglas-Peucker on a closed outline. A closed curve has no natural
     * endpoints to anchor the recursion, so the two extremes are made by
     * finding the point furthest from the start and splitting there — which
     * for a quadrilateral reliably lands on a corner diagonally opposite.
     */
    private fun simplifyClosed(contour: ContourTracer.Contour, epsilon: Double): List<Int> {
        val size = contour.size
        if (size < 4) return emptyList()

        var farthest = 0
        var farthestDistance = -1.0
        for (index in 1 until size) {
            val distance = hypot(
                (contour.xs[index] - contour.xs[0]).toDouble(),
                (contour.ys[index] - contour.ys[0]).toDouble(),
            )
            if (distance > farthestDistance) {
                farthestDistance = distance
                farthest = index
            }
        }

        val kept = sortedSetOf(0, farthest)
        simplify(contour, 0, farthest, epsilon, kept)
        simplify(contour, farthest, size - 1, epsilon, kept)
        // Index 0 closes the loop back from the tail, so it is already in.
        return kept.toList()
    }

    private fun simplify(
        contour: ContourTracer.Contour,
        from: Int,
        to: Int,
        epsilon: Double,
        kept: MutableSet<Int>,
    ) {
        if (to <= from + 1) return
        val x0 = contour.xs[from].toDouble()
        val y0 = contour.ys[from].toDouble()
        val x1 = contour.xs[to].toDouble()
        val y1 = contour.ys[to].toDouble()
        val dx = x1 - x0
        val dy = y1 - y0
        val length = hypot(dx, dy)

        var worst = -1
        var worstDistance = 0.0
        for (index in from + 1 until to) {
            val px = contour.xs[index].toDouble()
            val py = contour.ys[index].toDouble()
            val distance = if (length < 1e-9) {
                hypot(px - x0, py - y0)
            } else {
                abs(dy * px - dx * py + x1 * y0 - y1 * x0) / length
            }
            if (distance > worstDistance) {
                worstDistance = distance
                worst = index
            }
        }

        if (worst < 0 || worstDistance <= epsilon) return
        kept.add(worst)
        simplify(contour, from, worst, epsilon, kept)
        simplify(contour, worst, to, epsilon, kept)
    }

    private fun isConvex(xs: DoubleArray, ys: DoubleArray): Boolean {
        var sign = 0
        for (index in 0 until 4) {
            val next = (index + 1) and 3
            val after = (index + 2) and 3
            val cross = (xs[next] - xs[index]) * (ys[after] - ys[next]) -
                (ys[next] - ys[index]) * (xs[after] - xs[next])
            if (abs(cross) < 1e-9) return false
            val current = if (cross > 0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    private fun signedArea(xs: DoubleArray, ys: DoubleArray): Double {
        var total = 0.0
        for (index in 0 until 4) {
            val next = (index + 1) and 3
            total += xs[index] * ys[next] - xs[next] * ys[index]
        }
        return total / 2.0
    }

    private fun reverseInPlace(values: DoubleArray) {
        var low = 0
        var high = values.size - 1
        while (low < high) {
            val swap = values[low]
            values[low] = values[high]
            values[high] = swap
            low++
            high--
        }
    }
}
