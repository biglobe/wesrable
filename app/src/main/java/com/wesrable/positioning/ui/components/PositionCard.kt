package com.wesrable.positioning.ui.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
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
import com.wesrable.positioning.model.RoomAnchor
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val PIXELS_PER_METER = 24f
private const val COMPASS_RING_DP = 48
private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 8f

/** Below this the grid stops reading as a grid and starts reading as fill. */
private const val MIN_GRID_SPACING_PX = 22f

/** Grid intervals that make sense spoken aloud, for the scale bar to name. */
private val GRID_STEPS_METERS =
    listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)

/**
 * Coarsens the grid as the view zooms out, so it stays legible instead of
 * collapsing into a grey wash — and keeps the number of lines drawn bounded
 * however far out the user pinches.
 */
private fun gridStepMeters(pixelsPerMeter: Float): Double =
    GRID_STEPS_METERS.firstOrNull { it * pixelsPerMeter >= MIN_GRID_SPACING_PX }
        ?: GRID_STEPS_METERS.last()

/** Turns a screen offset clockwise; screen y grows downward, hence the signs. */
private fun rotateOffset(offset: Offset, degrees: Float): Offset {
    if (degrees == 0f) return offset
    val radians = Math.toRadians(degrees.toDouble())
    val sinR = sin(radians).toFloat()
    val cosR = cos(radians).toFloat()
    return Offset(
        offset.x * cosR - offset.y * sinR,
        offset.x * sinR + offset.y * cosR,
    )
}

@Composable
fun PositionCard(
    position: PositionEstimate,
    stepCount: Int,
    headingDeg: Float,
    trail: List<Pair<Double, Double>>,
    closurePoints: List<Pair<Double, Double>>,
    closureCount: Int,
    lastClosureDriftMeters: Double?,
    roomAnchors: List<RoomAnchor>,
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
                when {
                    closureCount == 0 ->
                        "No loops closed yet — walk a circuit of 60 m or more and come " +
                            "back past where you've already been."
                    else ->
                        "Loops closed: $closureCount" +
                            (lastClosureDriftMeters?.let {
                                " · last one pulled the trail %.1f m back into line".format(it)
                            } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Heading-up map, centered on you — the top means the direction you're " +
                    "facing, and the blue dot stays centered as you walk, with the trail " +
                    "and start point (grey dot) moving around it. Drag to look around, " +
                    "pinch to zoom, twist with two fingers to turn the map (twist until " +
                    "N is at the top for a north-up map). The ring shows where north is " +
                    "and its tick where you're facing; the bar gives the scale. " +
                    "Purple marks your rooms — a solid dot is where you stood to record " +
                    "one, a hollow dot with a ~ is a room from an earlier session placed " +
                    "by matching, so only accurate to a few meters. Green rings mark " +
                    "where a loop was closed.",
                style = MaterialTheme.typography.bodySmall,
            )
            var panOffset by remember { mutableStateOf(Offset.Zero) }
            var zoom by remember { mutableStateOf(1f) }

            // Degrees the user has twisted the map away from heading-up. Zero
            // means the top is the way they're facing, as before.
            var manualRotationDeg by remember { mutableStateOf(0f) }

            Box(Modifier.padding(top = 8.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, gestureRotation ->
                                val newZoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val appliedZoom = newZoom / zoom
                                // Pinching and twisting work about the middle
                                // of the box rather than about the walker, so
                                // panning away to inspect a far part of the
                                // trail and then zooming doesn't fling it off
                                // screen. That means the pan offset has to be
                                // scaled and turned to match.
                                panOffset = rotateOffset(
                                    panOffset * appliedZoom,
                                    gestureRotation,
                                ) + pan
                                zoom = newZoom
                                manualRotationDeg += gestureRotation
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
                    val pixelsPerMeter = PIXELS_PER_METER * zoom
                    val stepMeters = gridStepMeters(pixelsPerMeter)
                    val stepPx = (stepMeters * pixelsPerMeter).toFloat()

                    // Reference grid, anchored on the walker and stepped to
                    // whatever interval reads well at this zoom. Walking the
                    // grid across the box, rather than outwards from the
                    // origin, keeps it covering the view however far the user
                    // has panned.
                    var gx = originX - floor(originX / stepPx) * stepPx
                    while (gx < size.width) {
                        drawLine(Color(0xFFE0E0E0), Offset(gx, 0f), Offset(gx, size.height))
                        gx += stepPx
                    }
                    var gy = originY - floor(originY / stepPx) * stepPx
                    while (gy < size.height) {
                        drawLine(Color(0xFFE0E0E0), Offset(0f, gy), Offset(size.width, gy))
                        gy += stepPx
                    }

                    // Rotate every (east, north) point — relative to the
                    // *current position*, not the start point — into (right,
                    // forward) relative to the *current* heading, so "up" on
                    // screen always means "the way you're facing right now"
                    // — a heading-up map, like a phone nav app's walking
                    // mode. The whole trail is re-projected every frame, so
                    // it swings around consistently as you turn, and shifts
                    // as you walk so the dot stays put at the camera center.
                    //
                    // Twisting the map subtracts from that heading, which is
                    // what lets the user break out of heading-up and hold any
                    // orientation they like — including north-up, by twisting
                    // until the N marker sits at the top.
                    val effectiveHeadingDeg = headingDeg - manualRotationDeg
                    val headingRad = Math.toRadians(effectiveHeadingDeg.toDouble())
                    val sinH = sin(headingRad)
                    val cosH = cos(headingRad)

                    fun project(east: Double, north: Double): Offset {
                        val relEast = east - position.xMeters
                        val relNorth = north - position.yMeters
                        val forward = relEast * sinH + relNorth * cosH
                        val right = relEast * cosH - relNorth * sinH
                        return Offset(
                            originX + (right * pixelsPerMeter).toFloat(),
                            originY - (forward * pixelsPerMeter).toFloat(),
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

                    // Where the app recognised it had been before and pulled
                    // the trail back into line.
                    closurePoints.forEach { (east, north) ->
                        drawCircle(
                            Color(0xFF43A047),
                            radius = 6f,
                            center = project(east, north),
                            style = Stroke(width = 2f),
                        )
                    }

                    // Labeled rooms, pinned wherever they answered to their
                    // fingerprint. Drawn under the live dot so they never
                    // obscure the current position.
                    roomAnchors.forEach { anchor ->
                        val point = project(anchor.xMeters, anchor.yMeters)
                        if (anchor.isExact) {
                            // Recorded here: the position is simply known.
                            drawCircle(Color(0xFF8E24AA), radius = 5f, center = point)
                        } else {
                            // Inferred from where the room later matched, so
                            // only good to a few meters — drawn hollow to say so.
                            drawCircle(
                                Color(0xFF8E24AA),
                                radius = 5f,
                                center = point,
                                style = Stroke(width = 2f),
                            )
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            if (anchor.isExact) anchor.label else "~${anchor.label}",
                            point.x,
                            point.y - 9f,
                            AndroidPaint().apply {
                                color = android.graphics.Color.parseColor("#8E24AA")
                                textSize = 24f
                                textAlign = AndroidPaint.Align.CENTER
                                isAntiAlias = true
                                alpha = if (anchor.isExact) 255 else 160
                            },
                        )
                    }

                    val currentPoint = project(position.xMeters, position.yMeters)
                    val confidencePx = (position.confidenceRadiusMeters * pixelsPerMeter).toFloat()
                    drawCircle(Color(0x333D7FD9), radius = confidencePx.coerceAtLeast(4f), center = currentPoint)
                    drawCircle(Color(0xFF3D7FD9), radius = 10f, center = currentPoint)

                    // Scale bar. With zoom free to roam the grid alone no
                    // longer says how big anything is, so name the interval.
                    val barY = size.height - 14f
                    val barStart = 14f
                    val barEnd = barStart + stepPx
                    val barColor = Color(0xFF616161)
                    drawLine(barColor, Offset(barStart, barY), Offset(barEnd, barY), strokeWidth = 2f)
                    drawLine(barColor, Offset(barStart, barY - 4f), Offset(barStart, barY + 4f), strokeWidth = 2f)
                    drawLine(barColor, Offset(barEnd, barY - 4f), Offset(barEnd, barY + 4f), strokeWidth = 2f)
                    drawContext.canvas.nativeCanvas.drawText(
                        if (stepMeters >= 1.0) "%.0f m".format(stepMeters) else "%.1f m".format(stepMeters),
                        barStart,
                        barY - 8f,
                        AndroidPaint().apply {
                            color = android.graphics.Color.parseColor("#616161")
                            textSize = 22f
                            textAlign = AndroidPaint.Align.LEFT
                            isAntiAlias = true
                        },
                    )
                }
                NorthCompassRing(
                    headingDeg = headingDeg - manualRotationDeg,
                    forwardTickDeg = manualRotationDeg,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
                if (panOffset != Offset.Zero || zoom != 1f || manualRotationDeg != 0f) {
                    OutlinedButton(
                        onClick = {
                            panOffset = Offset.Zero
                            zoom = 1f
                            manualRotationDeg = 0f
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    ) {
                        Text("Reset view")
                    }
                }
            }
        }
    }
}

/**
 * A small ring showing where true north currently is, relative to the map
 * above: the "N" marker orbits the ring as the device turns — directly ahead
 * when facing north, behind when facing south, and so on.
 *
 * [headingDeg] is the bearing shown at the top of the map, which is the
 * device heading only while the map is left heading-up. [forwardTickDeg] is
 * where the direction the walker is actually facing has ended up once they
 * twist the map away from that, so the tick stops being a fixed decoration
 * and starts carrying the information the top of the screen used to.
 */
@Composable
private fun NorthCompassRing(
    headingDeg: Float,
    forwardTickDeg: Float,
    modifier: Modifier = Modifier,
) {
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
        // Which way the walker is facing. At the top while the map is
        // heading-up, swinging round as the map is twisted away from it.
        val tickRad = Math.toRadians(forwardTickDeg.toDouble())
        val tickSin = sin(tickRad).toFloat()
        val tickCos = cos(tickRad).toFloat()
        drawLine(
            color = Color(0xFF616161),
            start = Offset(centerX + ringRadius * tickSin, centerY - ringRadius * tickCos),
            end = Offset(
                centerX + (ringRadius - 6f) * tickSin,
                centerY - (ringRadius - 6f) * tickCos,
            ),
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
