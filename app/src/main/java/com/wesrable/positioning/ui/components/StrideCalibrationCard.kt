package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * Fits the step-length estimate to the person carrying the phone, by walking
 * a distance they already know and telling the app what it was.
 */
@Composable
fun StrideCalibrationCard(
    strideFactor: Float,
    calibrating: Boolean,
    walkedMeters: Double,
    onBegin: () -> Unit,
    onCancel: () -> Unit,
    onFinish: (Double) -> Unit,
    onReset: () -> Unit,
) {
    var actualInput by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Stride calibration", style = MaterialTheme.typography.titleMedium)
            Text(
                "Step length is guessed from how hard each step lands, using a " +
                    "constant from the literature rather than anything measured about " +
                    "you — and it reads high for the short steps you take indoors. " +
                    "Walk a distance you know and enter it, and the guess gets fitted " +
                    "to you. Because it is fitted against counted steps, it also " +
                    "absorbs any the detector missed.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!calibrating) {
                Text(
                    if (strideFactor == 1f) {
                        "Not calibrated — step lengths are the raw estimate."
                    } else {
                        "Calibrated: step lengths scaled to %.0f%% of the raw estimate."
                            .format(strideFactor * 100)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = onBegin) { Text("Start a measured walk") }
                    if (strideFactor != 1f) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onReset) { Text("Reset") }
                    }
                }
            } else {
                Text(
                    "Walking… the app makes it %.1f m so far. Stop on the mark, then " +
                        "enter the true distance.".format(walkedMeters),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = actualInput,
                        onValueChange = { actualInput = it },
                        label = { Text("Actual metres") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.width(160.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            actualInput.toDoubleOrNull()?.let(onFinish)
                            actualInput = ""
                        },
                        enabled = actualInput.toDoubleOrNull()?.let { it > 0 } == true,
                    ) {
                        Text("Save")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        actualInput = ""
                        onCancel()
                    }) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
