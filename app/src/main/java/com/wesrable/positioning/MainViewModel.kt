package com.wesrable.positioning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wesrable.positioning.fingerprint.FingerprintCrossValidation
import com.wesrable.positioning.fingerprint.FingerprintMatcher
import com.wesrable.positioning.fingerprint.FingerprintStore
import com.wesrable.positioning.fingerprint.MapStore
import com.wesrable.positioning.fingerprint.MarkerStore
import com.wesrable.positioning.fingerprint.SurveyStore
import com.wesrable.positioning.model.Anchor
import com.wesrable.positioning.model.BarometricReading
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.DetectedMarker
import com.wesrable.positioning.model.Fingerprint
import com.wesrable.positioning.model.MarkerLabel
import com.wesrable.positioning.model.Orientation
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import com.wesrable.positioning.model.RelocalizationState
import com.wesrable.positioning.model.RoomAnchor
import com.wesrable.positioning.model.RoomEstimate
import com.wesrable.positioning.model.RttMeasurement
import com.wesrable.positioning.model.RttProbe
import com.wesrable.positioning.model.StoredMap
import com.wesrable.positioning.model.SurveyReport
import com.wesrable.positioning.model.SurveyReportStatus
import com.wesrable.positioning.model.WifiSignal
import com.wesrable.positioning.positioning.PositioningEngine
import com.wesrable.positioning.scan.BleScanner
import com.wesrable.positioning.scan.RttRanger
import com.wesrable.positioning.scan.WifiScanner
import com.wesrable.positioning.sensors.BarometerSensor
import com.wesrable.positioning.sensors.MagnetometerSensor
import com.wesrable.positioning.sensors.OrientationSensor
import com.wesrable.positioning.sensors.StepDetector
import com.wesrable.positioning.sensors.StrideCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val orientation: Orientation = Orientation(),
    val wifiSignals: List<WifiSignal> = emptyList(),
    val bleSignals: List<BleSignal> = emptyList(),
    val barometer: BarometricReading? = null,
    val position: PositionEstimate = PositionEstimate(0.0, 0.0, PositionSource.UNAVAILABLE, 0.0),
    val stepCount: Int = 0,
    val pathLengthMeters: Double = 0.0,
    val strideFactor: Float = 1f,
    val calibratingStride: Boolean = false,
    val calibrationWalkedMeters: Double = 0.0,
    val trail: List<Pair<Double, Double>> = emptyList(),
    val closurePoints: List<Pair<Double, Double>> = emptyList(),
    val storedTrail: List<Pair<Double, Double>> = emptyList(),
    val relocalizationState: RelocalizationState = RelocalizationState.NO_MAP,
    val relocalizationUncertaintyMeters: Double? = null,
    val storedWaypointCount: Int = 0,
    val closureCount: Int = 0,
    val magneticClosureCount: Int = 0,
    val magneticClosureEnabled: Boolean = false,
    val lastClosureDriftMeters: Double? = null,
    val magneticMagnitudeUt: Float? = null,
    val roomEstimate: RoomEstimate = RoomEstimate(null, 0.0),
    val roomAnchors: List<RoomAnchor> = emptyList(),
    val savedRooms: List<Pair<String, Int>> = emptyList(),
    val wifiAvailable: Boolean = false,
    val bleAvailable: Boolean = false,
    val orientationAvailable: Boolean = false,
    val barometerAvailable: Boolean = false,
    val magnetometerAvailable: Boolean = false,
    val rttMeasurements: List<RttMeasurement> = emptyList(),
    val rttSupportedByDevice: Boolean = false,
    val rttRespondersInRange: Int = 0,
    val rttAccessPointsInRange: Int = 0,
    val uwbSupportedByDevice: Boolean = false,
    val rttProbes: List<RttProbe> = emptyList(),
    val rttProbing: Boolean = false,
    val rttProbeRun: Boolean = false,
    val surveying: Boolean = false,
    val surveyPointCount: Int = 0,
    val surveyReport: SurveyReport = SurveyReport(SurveyReportStatus.NOT_ENOUGH_POINTS),
    val surveyReportRunning: Boolean = false,
    /** Markers in view right now, nearest (largest on screen) first. */
    val visibleMarkers: List<DetectedMarker> = emptyList(),
    val markerLabels: List<MarkerLabel> = emptyList(),
    val markerScanning: Boolean = false,
    val isSensing: Boolean = false,
) {
    /**
     * The marker being looked at: the one filling most of the frame.
     *
     * Nearest-wins rather than centre-most, because a cabinet door is normally
     * approached rather than aimed at, and apparent size separates "the one I
     * am standing at" from "the one across the room" far more reliably than
     * position in frame does.
     */
    val focusedMarker: DetectedMarker? get() = visibleMarkers.firstOrNull()
}

private const val STALE_BLE_MILLIS = 12_000L
private const val RETRY_BACKOFF_MILLIS = 2_000L

/** Re-subscribes (re-registering sensor listeners / restarting scans) after
 * any upstream failure, so a transient radio/driver hiccup doesn't
 * permanently kill that one stream. */
private fun <T> Flow<T>.resilient(): Flow<T> = retry { delay(RETRY_BACKOFF_MILLIS); true }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val orientationSensor = OrientationSensor(application)
    private val wifiScanner = WifiScanner(application)
    private val bleScanner = BleScanner(application)
    private val rttRanger = RttRanger(application)
    private val barometerSensor = BarometerSensor(application)
    private val magnetometerSensor = MagnetometerSensor(application)
    private val stepDetector = StepDetector(application)
    private val engine = PositioningEngine()
    private val fingerprintStore = FingerprintStore(application)
    private val mapStore = MapStore(application)
    private val surveyStore = SurveyStore(application)
    private val markerStore = MarkerStore(application)
    private val strideCalibration = StrideCalibration(application)

    /** Populated via a one-time site calibration; empty by default because
     * anchor coordinates cannot be derived from RF alone (see README). */
    private val anchors: Map<String, Anchor> = emptyMap()

    /**
     * Whatever earlier sessions left behind, adopted so this one extends a
     * map of the place rather than starting a fresh drawing. Read once and
     * handed straight to the engine.
     */
    private val loadedMap = mapStore.load().also { engine.loadMap(it) }

    /**
     * Surveys from earlier walks. Adopted the same way the map is, and for a
     * sharper reason: the report refuses to compare samples taken within
     * seconds of each other, so a survey walked in one sitting yields far
     * fewer usable pairs than the same route walked again another day.
     */
    private val loadedSurvey = surveyStore.load().also { engine.loadSurvey(it) }

    private val _uiState = MutableStateFlow(
        UiState(
            savedRooms = fingerprintStore.labelCounts(),
            surveyPointCount = loadedSurvey.size,
            markerLabels = markerStore.all,
            storedTrail = loadedMap.trail,
            relocalizationState = engine.relocalizationState,
            storedWaypointCount = loadedMap.waypoints.size,
            wifiAvailable = wifiScanner.isAvailable,
            bleAvailable = bleScanner.isAvailable,
            orientationAvailable = orientationSensor.isAvailable,
            barometerAvailable = barometerSensor.isAvailable,
            magnetometerAvailable = magnetometerSensor.isAvailable,
            rttSupportedByDevice = rttRanger.isSupportedByDevice,
            uwbSupportedByDevice = rttRanger.isUwbSupportedByDevice,
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val bleSignals = LinkedHashMap<String, BleSignal>()
    private var latestOrientation = Orientation()
    private var latestWifi: List<WifiSignal> = emptyList()
    private var latestMagneticMagnitudeUt: Float? = null

    /**
     * Bumped on every completed WiFi scan. Android throttles scans to roughly
     * one per 30 s, so consecutive waypoints routinely carry identical WiFi
     * readings; loop closure uses this to tell "same place" apart from
     * "same stale scan".
     */
    private var wifiScanGeneration = 0L

    private var sensingJobs: List<Job> = emptyList()

    /**
     * Each sensor stream is launched as its own independent top-level child of
     * [viewModelScope] rather than nested inside one shared coroutine. Nesting
     * them (`launch { launch{}; launch{}; ... }`) would make them siblings
     * under one plain Job, and structured concurrency cancels *all* siblings
     * the moment *any one* of them throws — a single hiccup in, say, the BLE
     * stack would silently freeze WiFi scanning, step counting, and
     * orientation too. Independent top-level launches isolate failures to the
     * stream that caused them.
     */
    fun startSensing() {
        if (sensingJobs.any { it.isActive }) return
        _uiState.update { it.copy(isSensing = true) }

        sensingJobs = listOf(
            viewModelScope.launch {
                orientationSensor.readings().resilient().collect { orientation ->
                    latestOrientation = orientation
                    recompute()
                }
            },
            viewModelScope.launch {
                wifiScanner.scans().resilient().collect { signals ->
                    latestWifi = signals
                    wifiScanGeneration++
                    recompute()
                }
            },
            viewModelScope.launch {
                bleScanner.scans().resilient().collect { signal ->
                    bleSignals[signal.address] = signal
                    pruneStaleBle()
                    recompute()
                }
            },
            viewModelScope.launch {
                barometerSensor.readings().resilient().collect { reading ->
                    _uiState.update { it.copy(barometer = reading) }
                }
            },
            viewModelScope.launch {
                stepDetector.steps().resilient().collect { stepLength ->
                    engine.onStep(stepLength * strideCalibration.factor, latestOrientation.azimuthDeg)
                    // Offered per step rather than per scan: the engine keys
                    // waypoints off distance walked, which only changes here.
                    engine.observeSignals(
                        wifiRssi = latestWifi.associate { it.bssid to it.rssiDbm },
                        bleRssi = bleSignals.values.associate { it.identifier to it.rssiDbm },
                        magneticMagnitudeUt = latestMagneticMagnitudeUt,
                        wifiScanGeneration = wifiScanGeneration,
                    )
                    recompute()
                }
            },
            viewModelScope.launch {
                rttRanger.ranges().resilient().collect { measurements ->
                    _uiState.update {
                        it.copy(
                            rttMeasurements = measurements.sortedBy { m -> m.distanceMeters },
                            rttRespondersInRange = rttRanger.respondersInRange().size,
                            rttAccessPointsInRange = rttRanger.accessPointsInRange(),
                        )
                    }
                }
            },
            viewModelScope.launch {
                magnetometerSensor.readings().resilient().collect { magnitude ->
                    latestMagneticMagnitudeUt = magnitude
                    recompute()
                }
            },
        )
    }

    fun stopSensing() {
        sensingJobs.forEach { it.cancel() }
        sensingJobs = emptyList()
        saveMap()
        saveSurvey()
        _uiState.update { it.copy(isSensing = false) }
    }

    /**
     * Starts or stops capturing a dense survey sample every half-metre.
     *
     * Stopping persists immediately rather than waiting for the session to
     * end, because the walk that was just taken is the whole asset — losing it
     * to a swipe-away would cost the user another lap of the house.
     */
    fun setSurveying(enabled: Boolean) {
        engine.surveying = enabled
        if (!enabled) saveSurvey()
        _uiState.update {
            it.copy(surveying = enabled, surveyPointCount = engine.surveyPoints.size)
        }
    }

    /**
     * Scores the survey: leave-one-out cross-validation plus the resolution
     * curve. Run on [Dispatchers.Default] because it compares every pair of
     * samples, which at the survey cap is over 700,000 comparisons — a couple
     * of seconds of solid arithmetic, and nothing the main thread should be
     * doing.
     */
    fun runSurveyReport() {
        if (_uiState.value.surveyReportRunning) return
        val points = engine.surveyPoints
        _uiState.update { it.copy(surveyReportRunning = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) {
                FingerprintCrossValidation.report(points)
            }
            _uiState.update { it.copy(surveyReport = report, surveyReportRunning = false) }
        }
    }

    /**
     * Asks every visible access point to range, rather than trusting the
     * capability bit each one advertises. Deliberately a button and not part of
     * the continuous loop: ranging costs power, the platform rate-limits it,
     * and this is a question about the building that only needs asking once.
     */
    fun probeRtt() {
        if (_uiState.value.rttProbing) return
        _uiState.update { it.copy(rttProbing = true) }
        viewModelScope.launch {
            val probes = runCatching { rttRanger.probeAll() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(rttProbes = probes, rttProbing = false, rttProbeRun = true)
            }
        }
    }

    /**
     * Whether the camera is running. Kept off by default and switched on
     * deliberately: it is the one sensor here a user would reasonably want to
     * know about, and leaving it running to no purpose costs battery besides.
     */
    fun setMarkerScanning(enabled: Boolean) {
        _uiState.update {
            it.copy(
                markerScanning = enabled,
                visibleMarkers = if (enabled) it.visibleMarkers else emptyList(),
            )
        }
    }

    /** Called from the camera analyzer for every processed frame. */
    fun onMarkersDetected(markers: List<DetectedMarker>) {
        if (markers.isNotEmpty()) markerStore.noteSeen(markers.map { it.id })
        _uiState.update {
            it.copy(
                visibleMarkers = markers,
                markerLabels = if (markers.isEmpty()) it.markerLabels else markerStore.all,
            )
        }
    }

    /**
     * Names the marker currently in view, pinning it to where the walker is
     * standing — which, unlike everything inferred from signals, is exactly
     * where the furniture is, because the user walked to it to say so.
     */
    fun nameFocusedMarker(label: String) {
        val marker = _uiState.value.focusedMarker ?: return
        val position = engine.currentPosition()
        markerStore.name(marker.id, label, position.xMeters, position.yMeters)
        _uiState.update { it.copy(markerLabels = markerStore.all) }
    }

    fun forgetMarker(markerId: Int) {
        markerStore.forget(markerId)
        _uiState.update { it.copy(markerLabels = markerStore.all) }
    }

    fun clearSurvey() {
        engine.clearSurvey()
        surveyStore.clear()
        _uiState.update {
            it.copy(
                surveyPointCount = 0,
                surveyReport = SurveyReport(SurveyReportStatus.NOT_ENOUGH_POINTS),
            )
        }
    }

    private fun saveSurvey() {
        runCatching { surveyStore.save(engine.exportSurvey()) }
    }

    /**
     * Writes the session back into the stored map. The engine declines to
     * merge a session that never worked out where it was, since its
     * coordinates are relative to an origin nothing can find again — so
     * calling this is always safe, and does nothing when it should.
     */
    private fun saveMap() {
        if (engine.stepCount == 0) return
        runCatching { mapStore.save(engine.exportMap()) }
    }

    /**
     * Throws away the remembered map. Useful after moving house, or when the
     * map has been corrupted by a session that located itself wrongly — there
     * is otherwise no way back from a bad merge.
     */
    fun forgetMap() {
        mapStore.clear()
        engine.loadMap(StoredMap())
        _uiState.update {
            it.copy(
                storedTrail = emptyList(),
                storedWaypointCount = 0,
                relocalizationState = engine.relocalizationState,
                relocalizationUncertaintyMeters = null,
            )
        }
    }

    /**
     * Records a labeled fingerprint of the current WiFi/BLE RSSI + magnetic
     * field at wherever the device is right now — the "calibration walk"
     * step of room-level fingerprint matching. Call once per room (ideally a
     * few times per room, from different spots in it, for a more robust
     * match) before relying on [UiState.roomEstimate].
     */
    fun recordFingerprint(label: String) {
        val trimmedLabel = label.trim()
        if (trimmedLabel.isEmpty()) return

        fingerprintStore.add(
            Fingerprint(
                label = trimmedLabel,
                wifiRssi = latestWifi.associate { it.bssid to it.rssiDbm },
                bleRssi = bleSignals.values.associate { it.identifier to it.rssiDbm },
                magneticMagnitudeUt = latestMagneticMagnitudeUt ?: 0f,
                recordedAtMillis = System.currentTimeMillis(),
            )
        )
        // Where the user is standing right now is exactly where this room is,
        // no matching required — the best marker the map can have.
        engine.markRecordedRoom(trimmedLabel)
        _uiState.update { it.copy(savedRooms = fingerprintStore.labelCounts()) }
        recompute()
    }

    fun deleteFingerprint(label: String) {
        fingerprintStore.remove(label)
        engine.forgetRoom(label)
        _uiState.update { it.copy(savedRooms = fingerprintStore.labelCounts()) }
        recompute()
    }

    fun renameFingerprint(oldLabel: String, newLabel: String) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty() || trimmed == oldLabel) return
        fingerprintStore.rename(oldLabel, trimmed)
        engine.renameRoom(oldLabel, trimmed)
        _uiState.update { it.copy(savedRooms = fingerprintStore.labelCounts()) }
        recompute()
    }

    /**
     * Turns magnetic-sequence loop closure on or off. Off by default; see
     * [PositioningEngine.magneticClosureEnabled] for why it is the user's
     * call rather than a default.
     */
    /** Path length when stride calibration began; null when not calibrating. */
    private var calibrationStartPathLength: Double? = null

    fun beginStrideCalibration() {
        calibrationStartPathLength = engine.pathLengthMeters
        _uiState.update { it.copy(calibratingStride = true, calibrationWalkedMeters = 0.0) }
    }

    fun cancelStrideCalibration() {
        calibrationStartPathLength = null
        _uiState.update { it.copy(calibratingStride = false, calibrationWalkedMeters = 0.0) }
    }

    /**
     * Ends calibration, fitting the stride factor so the walk just taken comes
     * out as [actualMeters]. Fitted against counted steps, so it absorbs
     * missed ones as well as an overlong stride estimate.
     */
    fun finishStrideCalibration(actualMeters: Double) {
        val start = calibrationStartPathLength
        if (start != null) {
            strideCalibration.record(actualMeters, engine.pathLengthMeters - start)
        }
        calibrationStartPathLength = null
        _uiState.update {
            it.copy(
                calibratingStride = false,
                calibrationWalkedMeters = 0.0,
                strideFactor = strideCalibration.factor,
            )
        }
    }

    fun resetStrideCalibration() {
        strideCalibration.reset()
        _uiState.update { it.copy(strideFactor = strideCalibration.factor) }
    }

    fun setMagneticClosureEnabled(enabled: Boolean) {
        engine.magneticClosureEnabled = enabled
        _uiState.update { it.copy(magneticClosureEnabled = enabled) }
    }

    fun clearFingerprints() {
        // Collected before clearing, since afterwards the store has no idea
        // which labels existed to drop from the map.
        val labels = fingerprintStore.labelCounts().map { it.first }
        fingerprintStore.clear()
        labels.forEach { engine.forgetRoom(it) }
        _uiState.update {
            it.copy(
                savedRooms = emptyList(),
                roomEstimate = RoomEstimate(null, 0.0),
                roomAnchors = engine.roomAnchors,
            )
        }
    }

    private fun pruneStaleBle() {
        val cutoff = System.currentTimeMillis() - STALE_BLE_MILLIS
        bleSignals.entries.removeAll { it.value.lastSeenMillis < cutoff }
    }

    private fun recompute() {
        val ble = bleSignals.values.toList()
        val position = engine.fuse(latestWifi, ble, anchors)
        val roomEstimate = FingerprintMatcher.estimate(
            liveWifi = latestWifi,
            liveBle = ble,
            liveMagneticMagnitudeUt = latestMagneticMagnitudeUt,
            fingerprints = fingerprintStore.all,
        )
        // Pins the matched room to wherever the walker is standing, so the
        // labeled rooms can be drawn on the trail they were recorded along.
        engine.noteRoomMatch(roomEstimate)
        _uiState.update {
            it.copy(
                orientation = latestOrientation,
                wifiSignals = latestWifi.sortedByDescending { s -> s.rssiDbm },
                bleSignals = ble.sortedByDescending { s -> s.rssiDbm },
                position = position,
                stepCount = engine.stepCount,
                pathLengthMeters = engine.pathLengthMeters,
                strideFactor = strideCalibration.factor,
                calibrationWalkedMeters = calibrationStartPathLength
                    ?.let { start -> engine.pathLengthMeters - start } ?: 0.0,
                trail = engine.trail,
                closurePoints = engine.closurePoints,
                relocalizationState = engine.relocalizationState,
                relocalizationUncertaintyMeters = engine.relocalizationUncertaintyMeters,
                closureCount = engine.closureCount,
                magneticClosureCount = engine.magneticClosureCount,
                lastClosureDriftMeters = engine.lastClosureDriftMeters,
                magneticMagnitudeUt = latestMagneticMagnitudeUt,
                roomEstimate = roomEstimate,
                roomAnchors = engine.roomAnchors,
                surveyPointCount = engine.surveyPoints.size,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensing()
    }
}
