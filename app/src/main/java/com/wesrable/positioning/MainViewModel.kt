package com.wesrable.positioning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wesrable.positioning.fingerprint.FingerprintMatcher
import com.wesrable.positioning.fingerprint.FingerprintStore
import com.wesrable.positioning.fingerprint.MapStore
import com.wesrable.positioning.model.Anchor
import com.wesrable.positioning.model.BarometricReading
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.Fingerprint
import com.wesrable.positioning.model.Orientation
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import com.wesrable.positioning.model.RelocalizationState
import com.wesrable.positioning.model.RoomAnchor
import com.wesrable.positioning.model.RoomEstimate
import com.wesrable.positioning.model.StoredMap
import com.wesrable.positioning.model.WifiSignal
import com.wesrable.positioning.positioning.PositioningEngine
import com.wesrable.positioning.scan.BleScanner
import com.wesrable.positioning.scan.WifiScanner
import com.wesrable.positioning.sensors.BarometerSensor
import com.wesrable.positioning.sensors.MagnetometerSensor
import com.wesrable.positioning.sensors.OrientationSensor
import com.wesrable.positioning.sensors.StepDetector
import com.wesrable.positioning.sensors.StrideCalibration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val isSensing: Boolean = false,
)

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
    private val barometerSensor = BarometerSensor(application)
    private val magnetometerSensor = MagnetometerSensor(application)
    private val stepDetector = StepDetector(application)
    private val engine = PositioningEngine()
    private val fingerprintStore = FingerprintStore(application)
    private val mapStore = MapStore(application)
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

    private val _uiState = MutableStateFlow(
        UiState(
            savedRooms = fingerprintStore.labelCounts(),
            storedTrail = loadedMap.trail,
            relocalizationState = engine.relocalizationState,
            storedWaypointCount = loadedMap.waypoints.size,
            wifiAvailable = wifiScanner.isAvailable,
            bleAvailable = bleScanner.isAvailable,
            orientationAvailable = orientationSensor.isAvailable,
            barometerAvailable = barometerSensor.isAvailable,
            magnetometerAvailable = magnetometerSensor.isAvailable,
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
        _uiState.update { it.copy(isSensing = false) }
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
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensing()
    }
}
