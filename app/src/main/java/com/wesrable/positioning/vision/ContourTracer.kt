package com.wesrable.positioning.vision

/**
 * Finds the outlines of dark regions in a thresholded image, by Moore-neighbour
 * border following.
 *
 * A marker's black border becomes one connected dark region, so tracing that
 * region's outline yields the marker's outer edge as a closed loop of pixels —
 * which is what the quad fitter then squares up. Only outer borders are traced:
 * the white interior and the payload cells inside it are of no interest until
 * the marker has been located and unwarped.
 */
object ContourTracer {

    /**
     * Contours shorter than this are noise — a marker whose outline is under
     * ~40 px is far too small to read 16 payload cells out of anyway.
     */
    const val MIN_PERIMETER = 40

    /**
     * A contour longer than this fraction of the image perimeter is the frame
     * of the picture, a shadow across the whole scene, or the image border
     * itself, and can never be a marker in view.
     */
    const val MAX_PERIMETER_FRACTION = 0.9

    /** One traced outline, as the sequence of pixels walked around it. */
    class Contour(val xs: IntArray, val ys: IntArray) {
        val size: Int get() = xs.size
    }

    private val NEIGHBOR_DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val NEIGHBOR_DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    fun trace(image: BinaryImage): List<Contour> {
        val width = image.width
        val height = image.height
        val visited = BooleanArray(width * height)
        val maxPerimeter = (2.0 * (width + height) * MAX_PERIMETER_FRACTION).toInt()
        val contours = mutableListOf<Contour>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!image[x, y]) continue
                if (visited[y * width + x]) continue
                // A dark pixel whose left neighbour is light is the leftmost
                // pixel of some row of a region, so it lies on an outer border.
                // Scanning top-to-bottom, the first such pixel of a region is
                // always reached before any of its interior.
                if (image[x - 1, y]) continue

                val contour = follow(image, visited, x, y, maxPerimeter) ?: continue
                if (contour.size < MIN_PERIMETER) continue
                contours.add(contour)
            }
        }

        return contours
    }

    /**
     * Walks the border clockwise from [startX],[startY], having entered from
     * the light pixel to its west, and stops on returning to the start.
     *
     * The backtrack is carried as an absolute position rather than a direction
     * because it has to be re-expressed relative to each new pixel: the rule is
     * "resume scanning from where you came in", and where you came in moves
     * with you. Getting this wrong makes the walk cut corners on diagonal
     * edges, which quietly turns a marker's square outline into a five- or
     * six-sided polygon that the quad fitter then discards.
     */
    private fun follow(
        image: BinaryImage,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        maxPerimeter: Int,
    ): Contour? {
        val width = image.width
        val xs = ArrayList<Int>(64)
        val ys = ArrayList<Int>(64)

        var currentX = startX
        var currentY = startY
        var backtrackX = startX - 1
        var backtrackY = startY

        while (true) {
            xs.add(currentX)
            ys.add(currentY)
            visited[currentY * width + currentX] = true

            val entry = directionOf(backtrackX - currentX, backtrackY - currentY)
            var stepped = false
            for (offset in 1..8) {
                val direction = (entry + offset) and 7
                val nextX = currentX + NEIGHBOR_DX[direction]
                val nextY = currentY + NEIGHBOR_DY[direction]
                if (!image[nextX, nextY]) continue

                // The neighbour examined just before this one; light by
                // construction, since this is the first dark one found.
                val previous = (direction - 1) and 7
                backtrackX = currentX + NEIGHBOR_DX[previous]
                backtrackY = currentY + NEIGHBOR_DY[previous]
                currentX = nextX
                currentY = nextY
                stepped = true
                break
            }

            // An isolated pixel with no dark neighbour is not a contour.
            if (!stepped) return null

            if (currentX == startX && currentY == startY) {
                return Contour(xs.toIntArray(), ys.toIntArray())
            }
            if (xs.size > maxPerimeter) return null
        }
    }

    private fun directionOf(dx: Int, dy: Int): Int {
        for (index in 0 until 8) {
            if (NEIGHBOR_DX[index] == dx && NEIGHBOR_DY[index] == dy) return index
        }
        return 4
    }
}
