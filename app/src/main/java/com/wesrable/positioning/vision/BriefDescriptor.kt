package com.wesrable.positioning.vision

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Turns the neighbourhood of a keypoint into 256 bits, by comparing pairs of
 * nearby pixels and recording only which of each pair was brighter.
 *
 * Recording comparisons rather than brightnesses is what makes this survive a
 * room being photographed at two different times of day. Absolute values move
 * with the lighting; the *ordering* of two pixels a few millimetres apart
 * mostly does not. And a 256-bit string compares by counting differing bits,
 * which is one CPU instruction per 64 bits — so a live frame can be checked
 * against thousands of stored views without trouble.
 *
 * The sampling pattern is drawn once from a seeded generator, so every device
 * and every session produces comparable descriptors without shipping a table.
 * It is rotated by the keypoint's own orientation before use, which is what
 * lets a view recorded with the phone held one way match the same view
 * recorded with it held another.
 */
object BriefDescriptor {

    /** Bits per descriptor. 256 is the usual trade of size against precision. */
    const val BITS = 256

    /** Longs needed to hold [BITS]. */
    const val WORDS = BITS / 64

    /** Half-width of the patch the pairs are drawn from. */
    const val PATCH_RADIUS = 15

    /**
     * Smoothing applied before sampling. Without it a bit comparing two equally
     * bright pixels is decided by sensor noise rather than by the scene, and on
     * flat surfaces most bits are exactly that. See [GrayImage.boxBlurred].
     */
    const val SMOOTHING_RADIUS = 2

    /**
     * Pairs are drawn from a Gaussian rather than spread evenly: comparisons
     * close to the keypoint carry more information about it, and ones near the
     * patch edge are more likely to fall on something else entirely when the
     * viewpoint shifts.
     */
    private val PAIRS: Array<IntArray> by lazy {
        val random = Random(0xB21EF)
        Array(BITS) {
            intArrayOf(
                gaussianOffset(random), gaussianOffset(random),
                gaussianOffset(random), gaussianOffset(random),
            )
        }
    }

    private fun gaussianOffset(random: Random): Int {
        val spread = PATCH_RADIUS / 2.5
        var value: Double
        do {
            var u: Double
            var v: Double
            var s: Double
            do {
                u = random.nextDouble() * 2 - 1
                v = random.nextDouble() * 2 - 1
                s = u * u + v * v
            } while (s >= 1.0 || s == 0.0)
            value = u * kotlin.math.sqrt(-2.0 * kotlin.math.ln(s) / s) * spread
        } while (value < -PATCH_RADIUS || value > PATCH_RADIUS)
        return value.roundToInt()
    }

    /**
     * Describes every keypoint that sits far enough from the edge for its
     * whole patch to exist. Returns descriptors packed four longs per keypoint,
     * alongside the keypoints actually described.
     */
    fun describe(image: GrayImage, keypoints: List<Keypoint>): SceneSignature {
        // Keypoints are detected on the sharp image — blurring first would
        // weaken the corners they are found by — but described from the
        // smoothed one, where a bit means something.
        val smoothed = image.boxBlurred(SMOOTHING_RADIUS)
        val kept = keypoints.filter {
            it.x >= PATCH_RADIUS && it.y >= PATCH_RADIUS &&
                it.x < image.width - PATCH_RADIUS && it.y < image.height - PATCH_RADIUS
        }
        val descriptors = LongArray(kept.size * WORDS)

        kept.forEachIndexed { index, keypoint ->
            val cosAngle = cos(keypoint.angleRadians)
            val sinAngle = sin(keypoint.angleRadians)
            val base = index * WORDS

            for (bit in 0 until BITS) {
                val pair = PAIRS[bit]
                val firstX = keypoint.x + rotateX(pair[0], pair[1], cosAngle, sinAngle)
                val firstY = keypoint.y + rotateY(pair[0], pair[1], cosAngle, sinAngle)
                val secondX = keypoint.x + rotateX(pair[2], pair[3], cosAngle, sinAngle)
                val secondY = keypoint.y + rotateY(pair[2], pair[3], cosAngle, sinAngle)

                if (sampleClamped(smoothed, firstX, firstY) <
                    sampleClamped(smoothed, secondX, secondY)
                ) {
                    descriptors[base + bit / 64] =
                        descriptors[base + bit / 64] or (1L shl (bit % 64))
                }
            }
        }

        return SceneSignature(
            xs = IntArray(kept.size) { kept[it].x },
            ys = IntArray(kept.size) { kept[it].y },
            descriptors = descriptors,
            width = image.width,
            height = image.height,
        )
    }

    private fun rotateX(dx: Int, dy: Int, cosAngle: Double, sinAngle: Double): Int =
        (dx * cosAngle - dy * sinAngle).roundToInt()

    private fun rotateY(dx: Int, dy: Int, cosAngle: Double, sinAngle: Double): Int =
        (dx * sinAngle + dy * cosAngle).roundToInt()

    /**
     * Rotation can push a sample just past the edge even for a keypoint that
     * passed the margin test, since the rotated patch is a diamond rather than
     * a square. Clamping keeps that from throwing away an otherwise good
     * keypoint over one bit.
     */
    private fun sampleClamped(image: GrayImage, x: Int, y: Int): Int =
        image[x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1)]
}

/**
 * Everything remembered about one view: where the distinctive points were and
 * what each looked like. No pixels are kept — a signature is a few kilobytes of
 * numbers, and the image it came from cannot be reconstructed from it.
 */
class SceneSignature(
    val xs: IntArray,
    val ys: IntArray,
    val descriptors: LongArray,
    val width: Int,
    val height: Int,
) {
    val size: Int get() = xs.size

    fun descriptorWord(index: Int, word: Int): Long =
        descriptors[index * BriefDescriptor.WORDS + word]
}
