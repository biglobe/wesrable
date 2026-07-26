package com.wesrable.positioning.vision

import com.wesrable.positioning.model.DetectedMarker

/**
 * Finds printed markers in a camera frame and reports which ones they are.
 *
 * The whole pipeline is here in one place: threshold, trace outlines, keep the
 * quadrilaterals, unwarp each one and read its payload. Every stage exists to
 * throw candidates away — a typical frame yields hundreds of dark outlines,
 * a handful of convex quads, and either zero or a few genuine markers.
 *
 * Deliberately dependency-free. Pulling in OpenCV would add well over a hundred
 * megabytes of native libraries for six algorithms that fit in a few hundred
 * lines, and would put the one part of this app that most needs measuring
 * behind a wall the JVM harness cannot see through. Everything here runs on a
 * plain JVM against synthetic renders, which is how its detection rate at
 * distance, angle, blur and noise was established rather than assumed.
 */
class MarkerDetector(
    private val windowFraction: Double = AdaptiveThreshold.WINDOW_FRACTION,
    private val thresholdOffset: Int = AdaptiveThreshold.OFFSET,
    private val minSidePixels: Double = QuadFitter.MIN_SIDE_PIXELS,
) {

    /**
     * A marker appearing at two places in one frame is a contradiction — most
     * often the inner and outer edge of the same printed border both fitting a
     * quad. The larger reading is the outer one and is kept.
     */
    fun detect(image: GrayImage): List<DetectedMarker> {
        val binary = AdaptiveThreshold.apply(image, windowFraction, thresholdOffset)
        val contours = ContourTracer.trace(binary)
        val quads = QuadFitter.fit(contours, minSidePixels)

        val byId = LinkedHashMap<Int, DetectedMarker>()
        quads.forEach { quad ->
            val match = MarkerDecoder.decode(image, quad) ?: return@forEach
            val detected = DetectedMarker(
                id = match.id,
                centerXPixels = quad.centerX,
                centerYPixels = quad.centerY,
                sizePixels = quad.shortestSide,
                rotationSteps = match.rotation,
                bitErrors = match.bitErrors,
            )
            val existing = byId[match.id]
            if (existing == null || detected.sizePixels > existing.sizePixels) {
                byId[match.id] = detected
            }
        }

        return byId.values.sortedByDescending { it.sizePixels }
    }
}
