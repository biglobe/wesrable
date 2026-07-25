package com.wesrable.positioning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wesrable.positioning.model.Anchor
import com.wesrable.positioning.model.BarometricReading
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.Orientation
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import com.wesrable.positioning.model.WifiSignal
import com.wesrable.positioning.positioning.PositioningEngine
import com.wesrable.positioning.scan.BleScanner
import com.wesrable.positioning.scan.WifiScanner
import com.wesrable.positioning.sensors.BarometerSensor
import com.wesrable.positioning.sensors.OrientationSensor
import com.wesrable.positioning.sensors.StepDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val orientation: Orientation = Orientation(),
    val wifiSignals: List<WifiSignal> = emptyList(),
    val bleSignals: List<BleSignal> = emptyList(),
    val barometer: BarometricReading? = null,
    val position: PositionEstimate = PositionEstimate(0.0, 0.0, PositionSource.UNAVAILABLE, 0.0),
    val wifiAvailable: Boolean = false,
    val bleAvailable: Boolean = false,
    val orientationAvailable: Boolean = false,
    val barometerAvailable: Boolean = false,
    val isSensing: Boolean = false,
)

private const val STALE_BLE_MILLIS = 12_000L

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val orientationSensor = OrientationSensor(application)
    private val wifiScanner = WifiScanner(application)
    private val bleScanner = BleScanner(application)
    private val barometerSensor = BarometerSensor(application)
    private val stepDetector = StepDetector(application)
    private val engine = PositioningEngine()

    /** Populated via a one-time site calibration; empty by default because
     * anchor coordinates cannot be derived from RF alone (see README). */
    private val anchors: Map<String, Anchor> = emptyMap()

    private val _uiState = MutableStateFlow(
        UiState(
            wifiAvailable = wifiScanner.isAvailable,
            bleAvailable = bleScanner.isAvailable,
            orientationAvailable = orientationSensor.isAvailable,
            barometerAvailable = barometerSensor.isAvailable,
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val bleSignals = LinkedHashMap<String, BleSignal>()
    private var latestOrientation = Orientation()
    private var latestWifi: List<WifiSignal> = emptyList()

    private var sensingJob: Job? = null

    fun startSensing() {
        if (sensingJob?.isActive == true) return
        _uiState.update { it.copy(isSensing = true) }

        sensingJob = viewModelScope.launch {
            launch {
                orientationSensor.readings().collect { orientation ->
                    latestOrientation = orientation
                    recompute()
                }
            }
            launch {
                wifiScanner.scans().collect { signals ->
                    latestWifi = signals
                    recompute()
                }
            }
            launch {
                bleScanner.scans().collect { signal ->
                    bleSignals[signal.address] = signal
                    pruneStaleBle()
                    recompute()
                }
            }
            launch {
                barometerSensor.readings().collect { reading ->
                    _uiState.update { it.copy(barometer = reading) }
                }
            }
            launch {
                stepDetector.steps().collect { stepLength ->
                    engine.onStep(stepLength, latestOrientation.azimuthDeg)
                    recompute()
                }
            }
        }
    }

    fun stopSensing() {
        sensingJob?.cancel()
        sensingJob = null
        _uiState.update { it.copy(isSensing = false) }
    }

    private fun pruneStaleBle() {
        val cutoff = System.currentTimeMillis() - STALE_BLE_MILLIS
        bleSignals.entries.removeAll { it.value.lastSeenMillis < cutoff }
    }

    private fun recompute() {
        val ble = bleSignals.values.toList()
        val position = engine.fuse(latestWifi, ble, anchors)
        _uiState.update {
            it.copy(
                orientation = latestOrientation,
                wifiSignals = latestWifi.sortedByDescending { s -> s.rssiDbm },
                bleSignals = ble.sortedByDescending { s -> s.rssiDbm },
                position = position,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensing()
    }
}
