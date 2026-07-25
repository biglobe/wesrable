package com.wesrable.positioning.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.Orientation
import kotlin.math.roundToInt

@Composable
fun OrientationCard(orientation: Orientation, available: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Orientation (rotation-vector fusion)", style = MaterialTheme.typography.titleMedium)
            if (!available) {
                Text("Rotation sensor not available on this device.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompassRose(headingDeg = orientation.azimuthDeg)
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Heading: ${orientation.azimuthDeg.roundToInt()}°")
                    Text("Pitch: ${orientation.pitchDeg.roundToInt()}°")
                    Text("Roll: ${orientation.rollDeg.roundToInt()}°")
                }
            }
        }
    }
}

@Composable
private fun CompassRose(headingDeg: Float) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val radius = size.minDimension / 2
        drawCircle(color = Color(0xFFB0BEC5), radius = radius, style = Stroke(width = 3f))
        rotate(degrees = headingDeg) {
            drawLine(
                color = Color(0xFFD32F2F),
                start = Offset(size.width / 2, size.height / 2),
                end = Offset(size.width / 2, size.height / 2 - radius + 6),
                strokeWidth = 6f,
                cap = StrokeCap.Round,
            )
        }
    }
}
