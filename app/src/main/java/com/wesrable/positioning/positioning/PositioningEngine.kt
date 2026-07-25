package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.Anchor
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import com.wesrable.positioning.model.WifiSignal

/**
 * Fuses every offline signal source into one position estimate:
 *  - RF multilateration from WiFi/BLE RSSI, when >=3 calibrated anchors are visible.
 *  - Pedestrian dead reckoning (steps x heading), which runs continuously and
 *    needs no anchors or radios at all.
 *
 * When an RF fix is available it both (a) is reported directly, and (b) is
 * used to re-anchor the dead-reckoning tracker, which otherwise drifts
 * unbounded over distance. This mirrors how commercial indoor-positioning
 * SDKs combine PDR with periodic RF/UWB corrections.
 */
class PositioningEngine(
    private val deadReckoning: DeadReckoningTracker = DeadReckoningTracker(),
    private val loopClosure: LoopClosureTracker = LoopClosureTracker(),
) {

    /** Total footsteps counted since the engine was created. */
    val stepCount: Int get() = deadReckoning.totalSteps

    /** Every (east, north) position visited so far — the walked path. */
    val trail: List<Pair<Double, Double>> get() = deadReckoning.trail

    /** Loops closed so far, and by how much the last one shifted the trail. */
    var closureCount: Int = 0
        private set
    var lastClosureDriftMeters: Double? = null
        private set

    /** Where each loop was closed, for marking on the map. */
    val closurePoints: List<Pair<Double, Double>> get() = loopClosure.closurePoints

    fun onStep(stepLengthMeters: Float, headingDegrees: Float) {
        deadReckoning.onStep(stepLengthMeters, headingDegrees)
    }

    /**
     * Offers the current signal snapshot for automatic waypoint recording. If
     * the walker is recognised as having returned somewhere they've been, the
     * drift accumulated in between is removed from the trail in place.
     */
    fun observeSignals(
        wifiRssi: Map<String, Int>,
        bleRssi: Map<String, Int>,
        magneticMagnitudeUt: Float?,
        wifiScanGeneration: Long,
    ) {
        val position = deadReckoning.currentPosition()
        val closure = loopClosure.observe(
            xMeters = position.xMeters,
            yMeters = position.yMeters,
            pathLengthMeters = deadReckoning.pathLengthMeters,
            wifiRssi = wifiRssi,
            bleRssi = bleRssi,
            magneticMagnitudeUt = magneticMagnitudeUt,
            wifiScanGeneration = wifiScanGeneration,
        ) ?: return

        deadReckoning.rubberSheet(
            anchorPathLength = closure.anchorPathLengthMeters,
            endPathLength = closure.currentPathLengthMeters,
            driftEast = closure.driftEastMeters,
            driftNorth = closure.driftNorthMeters,
        )
        loopClosure.applyCorrection(
            anchorPathLength = closure.anchorPathLengthMeters,
            endPathLength = closure.currentPathLengthMeters,
            driftEast = closure.driftEastMeters,
            driftNorth = closure.driftNorthMeters,
        )
        closureCount++
        lastClosureDriftMeters = closure.driftMeters
    }

    /**
     * @param anchors map keyed by the same identifier used in [WifiSignal.bssid] /
     *   [BleSignal.identifier], populated via a one-time site calibration (not included
     *   automatically — real-world anchor coordinates aren't observable from RF alone).
     */
    fun fuse(
        wifiSignals: List<WifiSignal>,
        bleSignals: List<BleSignal>,
        anchors: Map<String, Anchor>,
    ): PositionEstimate {
        val rangings = buildList {
            wifiSignals.forEach { s ->
                anchors[s.bssid]?.let { add(Ranging(it, s.distanceMeters)) }
            }
            bleSignals.forEach { s ->
                anchors[s.identifier]?.let { add(Ranging(it, s.distanceMeters)) }
            }
        }

        val rfFix = Trilateration.solve(rangings)
        val drEstimate = deadReckoning.currentPosition()

        if (rfFix == null) {
            return drEstimate
        }

        val (rfPosition, rfResidual) = rfFix
        val rfConfidence = (rfResidual + 0.5).coerceAtLeast(0.5)

        // Simple inverse-variance weighted blend between the RF fix and the
        // dead-reckoning estimate.
        val wRf = 1.0 / (rfConfidence * rfConfidence)
        val wDr = 1.0 / (drEstimate.confidenceRadiusMeters * drEstimate.confidenceRadiusMeters + 0.01)
        val totalW = wRf + wDr

        val fusedX = (rfPosition[0] * wRf + drEstimate.xMeters * wDr) / totalW
        val fusedY = (rfPosition[1] * wRf + drEstimate.yMeters * wDr) / totalW

        return PositionEstimate(
            xMeters = fusedX,
            yMeters = fusedY,
            source = PositionSource.FUSED,
            confidenceRadiusMeters = kotlin.math.sqrt(1.0 / totalW),
        )
    }
}
