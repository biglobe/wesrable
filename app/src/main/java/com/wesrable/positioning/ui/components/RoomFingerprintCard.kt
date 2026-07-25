package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.RoomEstimate
import kotlin.math.roundToInt

/**
 * "Calibration walk" UI: record a labeled WiFi/BLE/magnetic signature at
 * wherever the device is right now, and see which recorded room the live
 * signal currently matches best. No coordinates, no anchors, nothing beyond
 * signals the device can already passively observe.
 */
@Composable
fun RoomFingerprintCard(
    savedRooms: List<Pair<String, Int>>,
    roomEstimate: RoomEstimate,
    onRecord: (String) -> Unit,
    onClear: () -> Unit,
) {
    var labelInput by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Room fingerprint map", style = MaterialTheme.typography.titleMedium)
            Text(
                "Walk to a room, name it below, and tap Record — repeat per room " +
                    "(a few samples per room from different spots helps). Once a few " +
                    "rooms are saved, your live signal is matched against them below.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (roomEstimate.label != null) {
                Text(
                    "You are probably in: ${roomEstimate.label} " +
                        "(${(roomEstimate.confidence * 100).roundToInt()}% confidence)",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text("No room match yet — record some fingerprints first.")
            }

            Row(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text("Room name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onRecord(labelInput)
                    labelInput = ""
                }) {
                    Text("Record")
                }
            }

            if (savedRooms.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                savedRooms.forEach { (label, count) ->
                    Text("$label — $count sample${if (count == 1) "" else "s"}")
                }
                OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Clear map")
                }
            }
        }
    }
}
