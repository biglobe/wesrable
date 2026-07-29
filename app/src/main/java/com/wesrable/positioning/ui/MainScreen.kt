package com.wesrable.positioning.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.MainViewModel
import com.wesrable.positioning.ui.components.BarometerCard
import com.wesrable.positioning.ui.components.BleListCard
import com.wesrable.positioning.ui.components.MarkerCard
import com.wesrable.positioning.ui.components.MarkerSheetScreen
import com.wesrable.positioning.ui.components.OrientationCard
import com.wesrable.positioning.ui.components.PermissionCard
import com.wesrable.positioning.ui.components.PositionCard
import com.wesrable.positioning.ui.components.RoomFingerprintCard
import com.wesrable.positioning.ui.components.RttCard
import com.wesrable.positioning.ui.components.StrideCalibrationCard
import com.wesrable.positioning.ui.components.SurveyCard
import com.wesrable.positioning.ui.components.WifiListCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var showingMarkerSheet by remember { mutableStateOf(false) }

    if (showingMarkerSheet) {
        MarkerSheetScreen(
            labels = state.markerLabels,
            onClose = { showingMarkerSheet = false },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Device Positioning") }) },
    ) { padding ->
        if (!permissionsGranted) {
            PermissionCard(
                modifier = Modifier.padding(padding),
                onRequestPermissions = onRequestPermissions,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OrientationCard(
                    orientation = state.orientation,
                    available = state.orientationAvailable,
                )
            }
            item {
                PositionCard(
                    position = state.position,
                    stepCount = state.stepCount,
                    pathLengthMeters = state.pathLengthMeters,
                    headingDeg = state.orientation.azimuthDeg,
                    trail = state.trail,
                    closurePoints = state.closurePoints,
                    closureCount = state.closureCount,
                    lastClosureDriftMeters = state.lastClosureDriftMeters,
                    roomAnchors = state.roomAnchors,
                    magneticClosureCount = state.magneticClosureCount,
                    magneticClosureEnabled = state.magneticClosureEnabled,
                    onMagneticClosureChange = { viewModel.setMagneticClosureEnabled(it) },
                    storedTrail = state.storedTrail,
                    relocalizationState = state.relocalizationState,
                    relocalizationUncertaintyMeters = state.relocalizationUncertaintyMeters,
                    storedWaypointCount = state.storedWaypointCount,
                    onForgetMap = { viewModel.forgetMap() },
                )
            }
            item {
                StrideCalibrationCard(
                    strideFactor = state.strideFactor,
                    calibrating = state.calibratingStride,
                    walkedMeters = state.calibrationWalkedMeters,
                    onBegin = { viewModel.beginStrideCalibration() },
                    onCancel = { viewModel.cancelStrideCalibration() },
                    onFinish = { viewModel.finishStrideCalibration(it) },
                    onReset = { viewModel.resetStrideCalibration() },
                )
            }
            item {
                MarkerCard(
                    scanning = state.markerScanning,
                    visibleMarkers = state.visibleMarkers,
                    focusedMarker = state.focusedMarker,
                    labels = state.markerLabels,
                    onSetScanning = { viewModel.setMarkerScanning(it) },
                    onMarkers = { viewModel.onMarkersDetected(it) },
                    onName = { viewModel.nameFocusedMarker(it) },
                    onForget = { viewModel.forgetMarker(it) },
                    onShowSheet = { showingMarkerSheet = true },
                )
            }
            item {
                RoomFingerprintCard(
                    savedRooms = state.savedRooms,
                    roomEstimate = state.roomEstimate,
                    onRecord = { label -> viewModel.recordFingerprint(label) },
                    onRename = { old, new -> viewModel.renameFingerprint(old, new) },
                    onDelete = { label -> viewModel.deleteFingerprint(label) },
                    onClear = { viewModel.clearFingerprints() },
                )
            }
            item {
                SurveyCard(
                    surveying = state.surveying,
                    pointCount = state.surveyPointCount,
                    report = state.surveyReport,
                    reportRunning = state.surveyReportRunning,
                    onSetSurveying = { viewModel.setSurveying(it) },
                    onRunReport = { viewModel.runSurveyReport() },
                    onClear = { viewModel.clearSurvey() },
                )
            }
            item {
                BarometerCard(
                    reading = state.barometer,
                    available = state.barometerAvailable,
                )
            }
            item {
                RttCard(
                    measurements = state.rttMeasurements,
                    supportedByDevice = state.rttSupportedByDevice,
                    respondersInRange = state.rttRespondersInRange,
                    accessPointsInRange = state.rttAccessPointsInRange,
                    uwbSupportedByDevice = state.uwbSupportedByDevice,
                    probes = state.rttProbes,
                    probing = state.rttProbing,
                    probeRun = state.rttProbeRun,
                    onProbe = { viewModel.probeRtt() },
                    azInitiatorSupported = state.azInitiatorSupported,
                    azCapabilityKey = state.azCapabilityKey,
                    characteristics = state.rttCharacteristics,
                )
            }
            item {
                WifiListCard(signals = state.wifiSignals, available = state.wifiAvailable)
            }
            item {
                BleListCard(signals = state.bleSignals, available = state.bleAvailable)
            }
        }
    }
}
