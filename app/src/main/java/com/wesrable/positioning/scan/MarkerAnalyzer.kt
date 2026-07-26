package com.wesrable.positioning.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.wesrable.positioning.model.DetectedMarker
import com.wesrable.positioning.vision.GrayImage
import com.wesrable.positioning.vision.MarkerDetector

/**
 * Turns camera frames into marker sightings.
 *
 * Nothing here leaves the device, and nothing is retained: each frame is read,
 * reduced to a handful of integer ids, and closed. The app holds no
 * `INTERNET` permission, so a frame has nowhere to go even if some future
 * mistake tried to send one.
 *
 * Frames arrive faster than they can be processed on a phone, which is fine and
 * intended — CameraX is configured to keep only the latest, so the detector
 * always works on what the camera is pointed at now rather than falling further
 * behind a queue.
 */
class MarkerAnalyzer(
    private val detector: MarkerDetector = MarkerDetector(),
    private val onMarkers: (List<DetectedMarker>) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val gray = image.toGrayImage() ?: return
            onMarkers(detector.detect(gray))
        } catch (e: RuntimeException) {
            // A dropped or malformed frame must not take the camera down; the
            // next one is milliseconds away.
            onMarkers(emptyList())
        } finally {
            image.close()
        }
    }
}

/**
 * Extracts the luminance plane, which is exactly the greyscale image the
 * detector wants — no colour conversion, no allocation beyond the one copy.
 *
 * The copy is unavoidable: cameras pad each row out to a hardware-friendly
 * stride, so the buffer is not a contiguous width-by-height image and cannot
 * be handed over as-is. Rows are copied individually when the stride differs
 * and in one block when it does not.
 */
internal fun ImageProxy.toGrayImage(): GrayImage? {
    val plane = planes.getOrNull(0) ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    // Interleaved luminance would need a per-pixel walk; no mainstream device
    // produces it for YUV_420_888, so bail rather than carry untested code.
    if (pixelStride != 1) return null

    val out = ByteArray(width * height)
    buffer.rewind()
    if (rowStride == width) {
        buffer.get(out, 0, minOf(buffer.remaining(), out.size))
    } else {
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val available = buffer.remaining()
            if (available <= 0) break
            val toRead = minOf(rowStride, available)
            buffer.get(row, 0, toRead)
            System.arraycopy(row, 0, out, y * width, minOf(width, toRead))
        }
    }

    return GrayImage(width, height, out)
}
