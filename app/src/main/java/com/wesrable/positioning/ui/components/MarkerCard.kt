package com.wesrable.positioning.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.wesrable.positioning.model.DetectedMarker
import com.wesrable.positioning.model.MarkerLabel
import com.wesrable.positioning.scan.MarkerAnalyzer
import java.util.concurrent.Executors

/**
 * The half of the problem that signals cannot solve.
 *
 * Passive WiFi, BLE and magnetics place the walker in the right room and, at
 * best, the right part of it — a metre or two, measured in this app rather than
 * assumed. Cabinets stand 30-60 cm apart and look identical, so no amount of
 * additional signal work distinguishes them. A printed marker does, exactly and
 * instantly, because identity stops being inferred and starts being read.
 */
@Composable
fun MarkerCard(
    scanning: Boolean,
    visibleMarkers: List<DetectedMarker>,
    focusedMarker: DetectedMarker?,
    labels: List<MarkerLabel>,
    onSetScanning: (Boolean) -> Unit,
    onMarkers: (List<DetectedMarker>) -> Unit,
    onName: (String) -> Unit,
    onForget: (Int) -> Unit,
    onShowSheet: () -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    val labelsById = remember(labels) { labels.associateBy { it.markerId } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Object markers", style = MaterialTheme.typography.titleMedium)
            Text(
                "Print the marker sheet, stick one on each cabinet, and point the " +
                    "camera at it. Signals get you to the right part of the right " +
                    "room; the marker says which cabinet. Everything is read on the " +
                    "device — no frame is stored, and the app holds no internet " +
                    "permission to send one with.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { onSetScanning(!scanning) }) {
                    Text(if (scanning) "Stop camera" else "Identify object")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onShowSheet) { Text("Marker sheet") }
            }

            if (scanning) {
                // Camera access is asked for here rather than at launch, so
                // that declining it costs only this feature. The rest of the
                // app positions perfectly well without a camera.
                val context = LocalContext.current
                var granted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted = it }

                if (!granted) {
                    Text(
                        "Reading markers needs camera access. Frames are analysed and " +
                            "discarded on the device — nothing is saved, and the app has " +
                            "no internet permission to send anything anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = { launcher.launch(android.Manifest.permission.CAMERA) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Allow camera")
                    }
                } else {
                    CameraPreview(
                        onMarkers = onMarkers,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .padding(top = 12.dp),
                    )

                    val focused = focusedMarker
                    Text(
                        when {
                            focused == null -> "No marker in view."
                            labelsById[focused.id] != null ->
                                "This is: ${labelsById[focused.id]!!.label}"
                            else -> "Marker ${focused.id} — not named yet."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    if (visibleMarkers.size > 1) {
                        Text(
                            "Also in view: " + visibleMarkers.drop(1).joinToString(", ") {
                                labelsById[it.id]?.label ?: "marker ${it.id}"
                            } + ". The nearest is taken as the one being looked at.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (focused != null) {
                        Row(Modifier.padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Name this object") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onName(nameInput)
                                    nameInput = ""
                                },
                                enabled = nameInput.isNotBlank(),
                            ) {
                                Text("Save")
                            }
                        }
                        Text(
                            "Stand at the object when you save — that position is pinned " +
                                "to the map exactly, rather than being guessed from signals.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (labels.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                labels.forEach { label ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%d · %s  (%.1f, %.1f m)".format(
                                label.markerId, label.label, label.xMeters, label.yMeters
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onForget(label.markerId) }) { Text("Forget") }
                    }
                }
            }
        }
    }
}

/**
 * CameraX preview plus frame analysis.
 *
 * Analysis runs on its own single-thread executor rather than the main one:
 * detection costs a few milliseconds per frame on a desktop and rather more on
 * a phone, and doing that on the UI thread would drop the preview to a
 * slideshow. `KEEP_ONLY_LATEST` means a slow frame is skipped rather than
 * queued, so what is analysed is always what the camera is pointed at now.
 */
@Composable
private fun CameraPreview(
    onMarkers: (List<DetectedMarker>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            provider = runCatching { providerFuture.get() }.getOrNull()
            provider?.bindMarkerUseCases(lifecycleOwner, previewView, executor, onMarkers)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())
    }
}

private fun ProcessCameraProvider.bindMarkerUseCases(
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    executor: java.util.concurrent.Executor,
    onMarkers: (List<DetectedMarker>) -> Unit,
) {
    val preview = Preview.Builder().build().apply {
        setSurfaceProvider(previewView.surfaceProvider)
    }

    // 1280x720 rather than the 640x480 default: apparent marker size scales
    // with resolution, and the detector was measured to read a marker down to
    // 16 px a side, so the larger frame roughly doubles the range at which a
    // sticker can be identified. Cost is borne by dropped frames, not latency.
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    )
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .apply { setAnalyzer(executor, MarkerAnalyzer(onMarkers = onMarkers)) }

    runCatching {
        unbindAll()
        bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
}
