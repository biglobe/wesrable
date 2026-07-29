package com.wesrable.positioning.vision

/**
 * An 8-bit greyscale image, which is exactly the luminance plane a camera
 * already produces — YUV's Y channel arrives separately from the colour, so
 * marker detection never has to convert or copy anything.
 */
class GrayImage(
    val width: Int,
    val height: Int,
    /** Row-major luminance, one byte per pixel, read unsigned. */
    val pixels: ByteArray,
) {
    init {
        require(pixels.size >= width * height) {
            "expected ${width * height} pixels, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /**
     * Box-blurred copy, via an integral image so the cost does not depend on
     * the radius.
     *
     * Descriptors must be sampled from this rather than from the raw frame.
     * A BRIEF bit records which of two pixels is brighter, and where the two
     * are genuinely equal — a flat wall, a plain cabinet door — that comparison
     * is decided by sensor noise, so the bit becomes a coin flip that lands
     * differently every frame. Measured on a synthetic room, sampling raw made
     * two renders of *the same view* match on 2 keypoints out of 31; the
     * information was there, drowned in per-pixel noise. Averaging over a small
     * neighbourhood first suppresses that noise while leaving real brightness
     * differences intact.
     */
    fun boxBlurred(radius: Int): GrayImage {
        if (radius <= 0) return this
        val integral = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                rowSum += this[x, y]
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }

        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                val area = (right - left + 1) * (bottom - top + 1)
                val sum = integral[(bottom + 1) * (width + 1) + (right + 1)] -
                    integral[top * (width + 1) + (right + 1)] -
                    integral[(bottom + 1) * (width + 1) + left] +
                    integral[top * (width + 1) + left]
                out[y * width + x] = (sum / area).toByte()
            }
        }
        return GrayImage(width, height, out)
    }

    /** Bilinear sample, for reading a marker cell that falls between pixels. */
    fun sampleAt(x: Double, y: Double): Int {
        val clampedX = x.coerceIn(0.0, (width - 1).toDouble())
        val clampedY = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = clampedX - x0
        val fy = clampedY - y0
        val top = this[x0, y0] * (1 - fx) + this[x1, y0] * fx
        val bottom = this[x0, y1] * (1 - fx) + this[x1, y1] * fx
        return (top * (1 - fy) + bottom * fy).toInt()
    }
}

/**
 * Binary image produced by thresholding; true means "dark", which for a marker
 * means part of its black border or one of its black payload cells.
 */
class BinaryImage(val width: Int, val height: Int, val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && dark[y * width + x]
}

/**
 * Local-mean adaptive thresholding.
 *
 * A single global threshold is useless here. A cabinet by a window and a
 * cabinet in the corner of the same room can differ by more brightness than
 * black differs from white, so any fixed cut-off blows out one and blacks out
 * the other. Comparing each pixel against the average of its own neighbourhood
 * instead makes the result depend on local contrast alone, which is what a
 * printed marker actually has, and which survives shadow, glare and a lamp on
 * one side of the room.
 *
 * The neighbourhood mean is taken from an integral image, so the cost is the
 * same whatever window size is chosen — four array reads per pixel rather than
 * a loop over the window.
 */
object AdaptiveThreshold {

    /**
     * Window half-width as a fraction of the image's shorter side. It has to be
     * comfortably larger than a marker cell (or the marker's own black cells
     * become "the local average" and vanish) and comfortably smaller than the
     * lighting variation being corrected for.
     */
    const val WINDOW_FRACTION = 0.06

    /**
     * How far below its neighbourhood a pixel must sit to count as dark. This
     * is what stops flat, featureless regions — a blank wall, where the mean is
     * the pixel — from dissolving into salt-and-pepper noise.
     */
    const val OFFSET = 7

    fun apply(
        image: GrayImage,
        windowFraction: Double = WINDOW_FRACTION,
        offset: Int = OFFSET,
    ): BinaryImage {
        val width = image.width
        val height = image.height
        val radius = (minOf(width, height) * windowFraction).toInt().coerceAtLeast(2)

        // Integral image with a zero row and column, so a window sum is always
        // four lookups with no bounds special-casing.
        val integral = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                rowSum += image[x, y]
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }

        val dark = BooleanArray(width * height)
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                val area = (right - left + 1) * (bottom - top + 1)
                val sum = integral[(bottom + 1) * (width + 1) + (right + 1)] -
                    integral[top * (width + 1) + (right + 1)] -
                    integral[(bottom + 1) * (width + 1) + left] +
                    integral[top * (width + 1) + left]
                dark[y * width + x] = image[x, y] * area < sum - offset * area
            }
        }

        return BinaryImage(width, height, dark)
    }
}
