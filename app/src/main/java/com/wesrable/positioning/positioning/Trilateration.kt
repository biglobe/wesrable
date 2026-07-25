package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.Anchor

data class Ranging(val anchor: Anchor, val distanceMeters: Double)

/**
 * Least-squares multilateration: given >=3 anchors at known coordinates and a
 * (noisy) distance estimate to each, solves for the most likely 2D position.
 *
 * This linearizes the system of circle equations by subtracting the last
 * anchor's equation from every other one, turning it into a linear
 * least-squares problem solved via the normal equations. Standard technique
 * for RSSI-based/UWB/RTT multilateration alike — only the distance inputs
 * differ in accuracy.
 */
object Trilateration {

    /** Returns the solved position plus a rough confidence radius (RMS residual), or null if under-determined. */
    fun solve(rangings: List<Ranging>): Pair<DoubleArray, Double>? {
        if (rangings.size < 3) return null

        val reference = rangings.last()
        val others = rangings.dropLast(1)

        // Build A * [x, y]^T = b
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0
        var b1 = 0.0; var b2 = 0.0

        for (r in others) {
            val xi = r.anchor.xMeters
            val yi = r.anchor.yMeters
            val di = r.distanceMeters
            val xn = reference.anchor.xMeters
            val yn = reference.anchor.yMeters
            val dn = reference.distanceMeters

            val rowA1 = 2 * (xi - xn)
            val rowA2 = 2 * (yi - yn)
            val rowB = (xi * xi - xn * xn) + (yi * yi - yn * yn) - di * di + dn * dn

            a11 += rowA1 * rowA1
            a12 += rowA1 * rowA2
            a22 += rowA2 * rowA2
            b1 += rowA1 * rowB
            b2 += rowA2 * rowB
        }

        val det = a11 * a22 - a12 * a12
        if (kotlin.math.abs(det) < 1e-9) return null

        val x = (a22 * b1 - a12 * b2) / det
        val y = (a11 * b2 - a12 * b1) / det

        val residualSumSq = rangings.sumOf { r ->
            val dx = r.anchor.xMeters - x
            val dy = r.anchor.yMeters - y
            val predicted = kotlin.math.sqrt(dx * dx + dy * dy)
            (predicted - r.distanceMeters).let { it * it }
        }
        val rms = kotlin.math.sqrt(residualSumSq / rangings.size)

        return doubleArrayOf(x, y) to rms
    }
}
