package com.wesrable.positioning.vision

/**
 * Reads a marker's identity out of a located quadrilateral.
 *
 * Given the four corners, the payload cells sit at known positions on the unit
 * square, so each is sampled through the homography, the 36 cell brightnesses
 * are split into black and white by a threshold derived from the marker itself,
 * and the resulting bits are looked up in the dictionary.
 */
object MarkerDecoder {

    /**
     * Fraction of each cell sampled, centred. Cell edges are where printing
     * bleed, motion blur and the interpolation between neighbouring pixels all
     * land, so only the middle is trusted; sampling several points across it
     * rather than one pixel makes a single speck of dust or dead pixel
     * harmless.
     */
    const val CELL_SAMPLE_FRACTION = 0.6

    /** Samples per side within each cell, so this squared per cell. */
    const val SAMPLES_PER_CELL = 3

    /**
     * Border cells allowed to come out white before the candidate is rejected.
     * Zero would be too strict — a corner estimated a pixel or two off can clip
     * one border cell — but the border is 20 cells, so allowing a couple still
     * throws out essentially every non-marker quad in a room.
     */
    const val MAX_BORDER_ERRORS = 2

    /**
     * Minimum spread between the darkest and lightest cell. A quad cut from a
     * flat dark surface has no contrast to split, and without this test the
     * threshold would slice the noise in half and hand back arbitrary bits.
     */
    const val MIN_CONTRAST = 25

    fun decode(image: GrayImage, quad: Quad): MarkerDictionary.Match? {
        val homography = Homography.squareTo(quad) ?: return null
        val cells = MarkerDictionary.CELLS

        val brightness = IntArray(cells * cells)
        for (row in 0 until cells) {
            for (col in 0 until cells) {
                brightness[row * cells + col] = sampleCell(image, homography, row, col) ?: return null
            }
        }

        val darkest = brightness.min()
        val lightest = brightness.max()
        if (lightest - darkest < MIN_CONTRAST) return null
        val threshold = otsu(brightness)

        // The border must be black all the way round. This single test is what
        // removes door frames, picture frames and shadow edges: they pass the
        // quad filter easily and fail here almost without exception.
        var borderErrors = 0
        for (row in 0 until cells) {
            for (col in 0 until cells) {
                val isBorder = row == 0 || col == 0 || row == cells - 1 || col == cells - 1
                if (!isBorder) continue
                if (brightness[row * cells + col] > threshold) borderErrors++
            }
        }
        if (borderErrors > MAX_BORDER_ERRORS) return null

        var bits = 0
        for (row in 0 until MarkerDictionary.GRID) {
            for (col in 0 until MarkerDictionary.GRID) {
                val value = brightness[(row + 1) * cells + (col + 1)]
                // White payload cell is a 1, matching MarkerDictionary.pattern.
                if (value > threshold) bits = bits or (1 shl (row * MarkerDictionary.GRID + col))
            }
        }

        return MarkerDictionary.match(bits)
    }

    private fun sampleCell(
        image: GrayImage,
        homography: Homography,
        row: Int,
        col: Int,
    ): Int? {
        val cells = MarkerDictionary.CELLS.toDouble()
        val margin = (1.0 - CELL_SAMPLE_FRACTION) / 2.0
        var total = 0
        var count = 0

        for (sy in 0 until SAMPLES_PER_CELL) {
            for (sx in 0 until SAMPLES_PER_CELL) {
                val within = { index: Int ->
                    margin + CELL_SAMPLE_FRACTION * (index + 0.5) / SAMPLES_PER_CELL
                }
                val u = (col + within(sx)) / cells
                val v = (row + within(sy)) / cells
                val point = homography.map(u, v)
                if (point[0].isNaN() || point[1].isNaN()) return null
                total += image.sampleAt(point[0], point[1])
                count++
            }
        }

        return if (count == 0) null else total / count
    }

    /**
     * Otsu's threshold over the 36 cell brightnesses — the split that best
     * separates them into two groups.
     *
     * Deriving the threshold from the marker's own cells rather than from the
     * image is what makes a marker in shadow and a marker in direct sun decode
     * identically: both have black cells and white cells, whatever their
     * absolute brightness, and the only question is where the gap between them
     * falls.
     */
    private fun otsu(values: IntArray): Int {
        val histogram = IntArray(256)
        values.forEach { histogram[it.coerceIn(0, 255)]++ }
        val total = values.size

        var sum = 0.0
        for (level in 0 until 256) sum += level.toDouble() * histogram[level]

        var sumBackground = 0.0
        var countBackground = 0
        var best = 0
        var bestVariance = -1.0

        for (level in 0 until 256) {
            countBackground += histogram[level]
            if (countBackground == 0) continue
            val countForeground = total - countBackground
            if (countForeground == 0) break

            sumBackground += level.toDouble() * histogram[level]
            val meanBackground = sumBackground / countBackground
            val meanForeground = (sum - sumBackground) / countForeground
            val variance = countBackground.toDouble() * countForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)

            if (variance > bestVariance) {
                bestVariance = variance
                best = level
            }
        }

        return best
    }
}
