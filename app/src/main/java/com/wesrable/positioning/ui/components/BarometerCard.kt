package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.BarometricReading

@Composable
fun BarometerCard(reading: BarometricReading?, available: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Altitude / floor (barometer)", style = MaterialTheme.typography.titleMedium)
            when {
                !available -> Text("Barometer not available on this device.")
                reading == null -> Text("Waiting for a reading…")
                else -> {
                    Text("Pressure: ${"%.2f".format(reading.pressureHpa)} hPa")
                    Text("Relative altitude: ${"%.1f".format(reading.altitudeMeters)} m")
                    Text("Estimated floor change: ${if (reading.relativeFloor >= 0) "+" else ""}${reading.relativeFloor}")
                }
            }
        }
    }
}
