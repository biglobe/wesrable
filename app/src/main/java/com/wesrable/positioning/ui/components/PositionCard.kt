package com.wesrable.positioning.ui.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import kotlin.math.cos
import kotlin.math.sin

private const val PIXELS_PER_METER = 24f
private const val COMPASS_RING_DP = 48

@Composable
fun PositionCard(
    position: PositionEstimate,
    stepCount: Int,
    headingDeg: Float,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Position estimate (relative to start point)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Source: ${position.source.label()} · " +
                    "x=${"%.1f".format(position.xMeters)} m, y=${"%.1f".format(position.yMeters)} m · " +
                    "±${"%.1f".format(position.confidenceRadiusMeters)} m",
            )
            Text("Steps: $stepCount")
            Text(
                "Heading-up map — the top of the map always means the direction you're " +
                    "currently facing, so forward motion always renders as moving up from " +
                    "the origin. The ring shows where north currently is relative to that.",
                style = MaterialTheme.typography.bodySmall,
            )
            Box(Modifier.padding(top = 8.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    val centerX = size.width / 2
                    val centerY = size.height / 2

                    // Reference grid, one line per meter (screen-fixed; the
                    // map rotates with heading, this grid doesn't represent N/S/E/W).
                    var gx = 0f
                    while (gx < size.width) {
                        drawLine(Color(0xFFE0E0E0), Offset(centerX + gx, 0f), Offset(centerX + gx, size.height))
                        drawLine(Color(0xFFE0E0E0), Offset(centerX - gx, 0f), Offset(centerX - gx, size.height))
                        gx += PIXELS_PER_METER
                    }
                    var gy = 0f
                    while (gy < size.height) {
                        drawLine(Color(0xFFE0E0E0), Offset(0f, centerY + gy), Offset(size.width, centerY + gy))
                        drawLine(Color(0xFFE0E0E0), Offset(0f, centerY - gy), Offset(size.width, centerY - gy))
                        gy += PIXELS_PER_METER
                    }

                    // Origin (start point).
                    drawCircle(Color(0xFF9E9E9E), radius = 5f, center = Offset(centerX, centerY))

                    // Rotate the (east, north) displacement into (right, forward)
                    // relative to the *current* heading, so "up" on screen always
                    // means "the way you're facing right now" — a heading-up map,
                    // like a phone nav app's walking mode, rather than a fixed
                    // north-up one where "forward" only points up if you happen
                    // to be walking due north.
                    val headingRad = Math.toRadians(headingDeg.toDouble())
                    val east = position.xMeters
                    val north = position.yMeters
                    val forward = east * sin(headingRad) + north * cos(headingRad)
                    val right = east * cos(headingRad) - north * sin(headingRad)

                    val px = centerX + (right * PIXELS_PER_METER).toFloat()
                    val py = centerY - (forward * PIXELS_PER_METER).toFloat()
                    val confidencePx = (position.confidenceRadiusMeters * PIXELS_PER_METER).toFloat()
                    drawCircle(Color(0x333D7FD9), radius = confidencePx.coerceAtLeast(4f), center = Offset(px, py))
                    drawCircle(Color(0xFF3D7FD9), radius = 10f, center = Offset(px, py))
                }
                NorthCompassRing(
                    headingDeg = headingDeg,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
        }
    }
}

/**
 * A small ring showing where true north currently is, relative to the
 * heading-up map above: the ring's top always represents "forward" (same
 * convention as the map), and the "N" marker orbits it as the device turns
 * — directly ahead when facing north, behind when facing south, etc.
 */
@Composable
private fun NorthCompassRing(headingDeg: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(COMPASS_RING_DP.dp)) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val ringRadius = size.minDimension / 2 - 4f
        val markerRadius = ringRadius - 6f

        drawCircle(
            color = Color(0xFFBDBDBD),
            radius = ringRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2f),
        )
        // Forward tick, fixed at the top — matches the map's heading-up convention.
        drawLine(
            color = Color(0xFF616161),
            start = Offset(centerX, centerY - ringRadius),
            end = Offset(centerX, centerY - ringRadius + 6f),
            strokeWidth = 3f,
        )

        // North sits at screen angle -heading from "up": turning right
        // (heading increases) swings north around to the left, and so on.
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val nx = centerX - (markerRadius * sin(headingRad)).toFloat()
        val ny = centerY - (markerRadius * cos(headingRad)).toFloat()

        drawContext.canvas.nativeCanvas.drawText(
            "N",
            nx,
            ny + 5f, // vertically center the glyph on the point
            AndroidPaint().apply {
                color = android.graphics.Color.parseColor("#D32F2F")
                textSize = 26f
                textAlign = AndroidPaint.Align.CENTER
                isAntiAlias = true
                isFakeBoldText = true
            },
        )
    }
}

private fun PositionSource.label(): String = when (this) {
    PositionSource.TRILATERATION -> "RF trilateration"
    PositionSource.DEAD_RECKONING -> "Dead reckoning"
    PositionSource.FUSED -> "Fused (RF + dead reckoning)"
    PositionSource.UNAVAILABLE -> "No signal yet"
}
