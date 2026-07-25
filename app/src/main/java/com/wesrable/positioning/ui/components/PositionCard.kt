package com.wesrable.positioning.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource

private const val PIXELS_PER_METER = 24f

@Composable
fun PositionCard(position: PositionEstimate, stepCount: Int, onCalibrateHeading: () -> Unit) {
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
                "If the dot moves the wrong way as you walk, the phone isn't held with " +
                    "its top pointed the way you're actually walking — face forward and tap:",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onCalibrateHeading, modifier = Modifier.padding(top = 4.dp)) {
                Text("I'm facing forward")
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(top = 8.dp)
            ) {
                val centerX = size.width / 2
                val centerY = size.height / 2

                // Grid, one line per meter.
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

                // Current estimate.
                val px = centerX + (position.xMeters * PIXELS_PER_METER).toFloat()
                val py = centerY - (position.yMeters * PIXELS_PER_METER).toFloat()
                val confidencePx = (position.confidenceRadiusMeters * PIXELS_PER_METER).toFloat()
                drawCircle(Color(0x333D7FD9), radius = confidencePx.coerceAtLeast(4f), center = Offset(px, py))
                drawCircle(Color(0xFF3D7FD9), radius = 10f, center = Offset(px, py))
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
