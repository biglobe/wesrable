package com.wesrable.positioning.vision

import kotlin.math.abs

/**
 * Maps the unit square onto a quadrilateral, so a marker photographed at an
 * angle can be read as though it were face-on.
 *
 * This is the step that lets a marker be recognised from anywhere in the room
 * rather than only from directly in front of it. A square viewed obliquely
 * projects to a general quadrilateral — parallel edges converge — so sampling
 * the payload cells on an evenly spaced grid across the *image* would drift
 * further off the true cell centres the further from square the view is. Going
 * through the projective transform instead samples where the cells actually
 * are.
 *
 * Uses Heckbert's closed form for the square-to-quad case rather than a general
 * eight-parameter solve: same answer, no linear algebra, and no chance of a
 * near-singular matrix on a nearly-degenerate quad.
 */
class Homography private constructor(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val e: Double,
    private val f: Double,
    private val g: Double,
    private val h: Double,
) {

    /** Where unit-square point ([u],[v]) lands in the image. */
    fun map(u: Double, v: Double): DoubleArray {
        val w = g * u + h * v + 1.0
        if (abs(w) < 1e-12) return doubleArrayOf(Double.NaN, Double.NaN)
        return doubleArrayOf((a * u + b * v + c) / w, (d * u + e * v + f) / w)
    }

    companion object {
        /**
         * Corners are taken in order: (0,0), (1,0), (1,1), (0,1) of the unit
         * square map to quad corners 0, 1, 2, 3.
         */
        fun squareTo(quad: Quad): Homography? {
            val x0 = quad.xs[0]
            val x1 = quad.xs[1]
            val x2 = quad.xs[2]
            val x3 = quad.xs[3]
            val y0 = quad.ys[0]
            val y1 = quad.ys[1]
            val y2 = quad.ys[2]
            val y3 = quad.ys[3]

            val sumX = x0 - x1 + x2 - x3
            val sumY = y0 - y1 + y2 - y3

            // Exactly parallel edges mean an affine view — head-on, or far
            // enough away that the perspective has flattened out.
            if (abs(sumX) < 1e-9 && abs(sumY) < 1e-9) {
                return Homography(
                    a = x1 - x0, b = x2 - x1, c = x0,
                    d = y1 - y0, e = y2 - y1, f = y0,
                    g = 0.0, h = 0.0,
                )
            }

            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val denominator = dx1 * dy2 - dx2 * dy1
            if (abs(denominator) < 1e-9) return null

            val g = (sumX * dy2 - dx2 * sumY) / denominator
            val h = (dx1 * sumY - sumX * dy1) / denominator

            return Homography(
                a = x1 - x0 + g * x1, b = x3 - x0 + h * x3, c = x0,
                d = y1 - y0 + g * y1, e = y3 - y0 + h * y3, f = y0,
                g = g, h = h,
            )
        }
    }
}
