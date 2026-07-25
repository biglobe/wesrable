package com.wesrable.positioning.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import kotlin.math.cos
import kotlin.math.sin

private const val PIXELS_PER_METER = 24f

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
                "Heading-up map — \"▲ forward\" always means the direction you're " +
                    "currently facing, not north, so forward motion should always render " +
                    "as moving up from the origin regardless of which way you're walking.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("▲ forward", style = MaterialTheme.typography.labelSmall)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                val centerX = size.width / 2
                val centerY = size.height / 2

                // Reference grid, one line per meter (screen-fixed; the map
                // rotates with heading, this grid doesn't represent N/S/E/W).
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

                // Forward-direction arrow at the origin, for orientation.
                drawLine(
                    color = Color(0xFF616161),
                    start = Offset(centerX, centerY),
                    end = Offset(centerX, centerY - 16f),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun PositionSource.label(): String = when (this) {
    PositionSource.TRILATERATION -> "RF trilateration"
    PositionSource.DEAD_RECKONING -> "Dead reckoning"
    PositionSource.FUSED -> "Fused (RF + dead reckoning)"
    PositionSource.UNAVAILABLE -> "No signal yet"
}
