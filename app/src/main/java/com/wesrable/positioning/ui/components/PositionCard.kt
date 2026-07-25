package com.wesrable.positioning.ui.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
    trail: List<Pair<Double, Double>>,
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
                "Heading-up map, centered on you — the top always means the direction " +
                    "you're currently facing, and the blue dot stays centered as you walk, " +
                    "with the trail and start point (grey dot) moving around it. Drag to " +
                    "look around the rest of the trail; the ring shows where north is.",
                style = MaterialTheme.typography.bodySmall,
            )
            var panOffset by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.padding(top = 8.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                panOffset += dragAmount
                            }
                        }
                ) {
                    // Everything is drawn relative to the *current position*,
                    // not the start point — the camera follows the user, with
                    // panOffset (from dragging) as a manual override so they
                    // can look around the rest of the trail. Origin at
                    // panOffset == (centerX, centerY): where the live dot sits.
                    val originX = size.width / 2 + panOffset.x
                    val originY = size.height / 2 + panOffset.y

                    // Reference grid, one line per meter.
                    var gx = 0f
                    while (gx < size.width) {
                        drawLine(Color(0xFFE0E0E0), Offset(originX + gx, 0f), Offset(originX + gx, size.height))
                        drawLine(Color(0xFFE0E0E0), Offset(originX - gx, 0f), Offset(originX - gx, size.height))
                        gx += PIXELS_PER_METER
                    }
                    var gy = 0f
                    while (gy < size.height) {
                        drawLine(Color(0xFFE0E0E0), Offset(0f, originY + gy), Offset(size.width, originY + gy))
                        drawLine(Color(0xFFE0E0E0), Offset(0f, originY - gy), Offset(size.width, originY - gy))
                        gy += PIXELS_PER_METER
                    }

                    // Rotate every (east, north) point — relative to the
                    // *current position*, not the start point — into (right,
                    // forward) relative to the *current* heading, so "up" on
                    // screen always means "the way you're facing right now"
                    // — a heading-up map, like a phone nav app's walking
                    // mode. The whole trail is re-projected every frame, so
                    // it swings around consistently as you turn, and shifts
                    // as you walk so the dot stays put at the camera center.
                    val headingRad = Math.toRadians(headingDeg.toDouble())
                    val sinH = sin(headingRad)
                    val cosH = cos(headingRad)

                    fun project(east: Double, north: Double): Offset {
                        val relEast = east - position.xMeters
                        val relNorth = north - position.yMeters
                        val forward = relEast * sinH + relNorth * cosH
                        val right = relEast * cosH - relNorth * sinH
                        return Offset(
                            originX + (right * PIXELS_PER_METER).toFloat(),
                            originY - (forward * PIXELS_PER_METER).toFloat(),
                        )
                    }

                    if (trail.size > 1) {
                        val path = Path()
                        trail.forEachIndexed { index, (east, north) ->
                            val point = project(east, north)
                            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
                        drawPath(path, color = Color(0xFF90A4CC), style = Stroke(width = 4f))
                    }

                    // Start point.
                    drawCircle(Color(0xFF9E9E9E), radius = 5f, center = project(0.0, 0.0))

                    val currentPoint = project(position.xMeters, position.yMeters)
                    val confidencePx = (position.confidenceRadiusMeters * PIXELS_PER_METER).toFloat()
                    drawCircle(Color(0x333D7FD9), radius = confidencePx.coerceAtLeast(4f), center = currentPoint)
                    drawCircle(Color(0xFF3D7FD9), radius = 10f, center = currentPoint)
                }
                NorthCompassRing(
                    headingDeg = headingDeg,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
                if (panOffset != Offset.Zero) {
                    OutlinedButton(
                        onClick = { panOffset = Offset.Zero },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    ) {
                        Text("Recenter")
                    }
                }
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
