package com.wesrable.positioning.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.MarkerLabel
import com.wesrable.positioning.vision.MarkerDictionary

/**
 * The printable markers themselves.
 *
 * Drawn from the same [MarkerDictionary] the decoder reads, so the printed
 * pattern and the expected pattern cannot drift apart — the usual failure of
 * generating markers with one tool and detecting them with another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerSheetScreen(labels: List<MarkerLabel>, onClose: () -> Unit) {
    val labelsById = labels.associateBy { it.markerId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marker sheet") },
                actions = { TextButton(onClick = onClose) { Text("Done") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("How to use these", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Screenshot this page and print it, or photograph the screen " +
                                "and print that. Cut out one marker per cabinet and stick " +
                                "it on the front.\n\n" +
                                "Two things matter and nothing else does. Keep the white " +
                                "margin around each marker — the detector finds a marker " +
                                "by its black border against light surroundings, and a " +
                                "marker trimmed flush has nothing to stand out against. " +
                                "And print them large enough: measured against synthetic " +
                                "photographs, a 6 cm marker reads reliably to about 1.5 m " +
                                "and a 15 cm one to 4 m, at any angle up to 60 degrees " +
                                "off-square.\n\n" +
                                "There are ${MarkerDictionary.size} markers, which is " +
                                "${MarkerDictionary.size} distinguishable objects. They " +
                                "are chosen to stay far apart from each other even when " +
                                "rotated, so a misread returns nothing rather than the " +
                                "wrong cabinet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            items(((MarkerDictionary.size + 1) / 2)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (column in 0 until 2) {
                        val id = row * 2 + column
                        if (id >= MarkerDictionary.size) {
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            continue
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            MarkerImage(id, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(
                                labelsById[id]?.label ?: "marker $id",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One marker, drawn cell by cell with a white quiet zone around it.
 *
 * Always pure black on pure white regardless of app theme: these are meant to
 * be printed, and a marker rendered in a dark theme's off-white would print
 * with less contrast for no reason.
 */
@Composable
fun MarkerImage(id: Int, modifier: Modifier = Modifier) {
    val pattern = MarkerDictionary.pattern(id)
    val cells = MarkerDictionary.CELLS
    // One cell of white all round, which is what gives the black border
    // something to contrast against once the marker is cut out and stuck up.
    val total = cells + 2

    Canvas(modifier) {
        val cell = minOf(size.width, size.height) / total
        drawRect(Color.White, Offset.Zero, size)
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                if (!pattern[row][column]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset((column + 1) * cell, (row + 1) * cell),
                    // Half a pixel of overlap, so neighbouring black cells do
                    // not print with hairline white seams between them.
                    size = Size(cell + 0.5f, cell + 0.5f),
                )
            }
        }
    }
}
