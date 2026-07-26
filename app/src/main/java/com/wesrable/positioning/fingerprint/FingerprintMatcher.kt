package com.wesrable.positioning.fingerprint

import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.Fingerprint
import com.wesrable.positioning.model.RoomEstimate
import com.wesrable.positioning.model.WifiSignal
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Weighted k-nearest-neighbor room matching against stored fingerprints —
 * the classic indoor-fingerprinting technique (as in RADAR, Bahl &
 * Padmanabhan 2000): compare the live WiFi/BLE RSSI + magnetic-field
 * signature to every stored sample, and let the closest few samples vote on
 * the most likely room. No coordinates, no anchors, no radio connection —
 * just pattern matching against a map the user walked and recorded once.
 */
object FingerprintMatcher {

    private const val K_NEIGHBORS = 3
    private const val MISSING_SIGNAL_PENALTY_DB = 15.0
    private const val RSSI_WEIGHT = 1.0
    private const val MAGNETIC_WEIGHT = 0.3

    /**
     * The weakest signal a scan reports. A landmark absent from one side was
     * not "15 dB different" — it was below this.
     */
    const val DETECTION_FLOOR_DBM = -95.0

    fun estimate(
        liveWifi: List<WifiSignal>,
        liveBle: List<BleSignal>,
        liveMagneticMagnitudeUt: Float?,
        fingerprints: List<Fingerprint>,
    ): RoomEstimate {
        if (fingerprints.isEmpty()) return RoomEstimate(null, 0.0)

        val liveWifiRssi = liveWifi.associate { it.bssid to it.rssiDbm }
        val liveBleRssi = liveBle.associate { it.identifier to it.rssiDbm }
        if (liveWifiRssi.isEmpty() && liveBleRssi.isEmpty() && liveMagneticMagnitudeUt == null) {
            return RoomEstimate(null, 0.0)
        }

        val nearest = fingerprints
            .map { it to distance(liveWifiRssi, liveBleRssi, liveMagneticMagnitudeUt, it) }
            .sortedBy { it.second }
            .take(K_NEIGHBORS)

        val votes = mutableMapOf<String, Double>()
        nearest.forEach { (fingerprint, dist) ->
            votes[fingerprint.label] = (votes[fingerprint.label] ?: 0.0) + 1.0 / (dist + 1.0)
        }

        val (bestLabel, bestWeight) = votes.maxByOrNull { it.value } ?: return RoomEstimate(null, 0.0)
        val totalWeight = votes.values.sum()
        val confidence = if (totalWeight > 0) bestWeight / totalWeight else 0.0

        return RoomEstimate(bestLabel, confidence, nearest.first().second)
    }

    /**
     * A fingerprint recorded on a device with no working magnetometer stores
     * 0 uT, which is not a field strength any point on Earth has — Earth's is
     * 25-65 uT. Treated as "unknown" rather than compared, so such a sample is
     * not handed a ~50 uT penalty against every live reading.
     */
    private fun distance(
        liveWifi: Map<String, Int>,
        liveBle: Map<String, Int>,
        liveMagnetic: Float?,
        fingerprint: Fingerprint,
    ): Double = signalDistance(
        wifiA = liveWifi,
        bleA = liveBle,
        magneticA = liveMagnetic,
        wifiB = fingerprint.wifiRssi,
        bleB = fingerprint.bleRssi,
        magneticB = fingerprint.magneticMagnitudeUt.takeIf { it != 0f },
    ) ?: Double.MAX_VALUE

    /**
     * How unalike two signal signatures are: a weighted mean over whichever
     * comparisons can actually be made, rather than their sum. Summing works
     * for ranking — every candidate is compared against the same live snapshot
     * — but leaves the result on a scale that silently changes with how many
     * signal types happen to be available, so no fixed number means anything
     * absolute. Averaging keeps the figure comparable, which is what lets
     * [RoomEstimate.nearestDistanceDb] be tested against a threshold to decide
     * whether the walker is near any mapped room at all.
     *
     * Null when the two have no signal type in common and there is genuinely
     * nothing to compare — distinct from "compared, and found identical".
     *
     * Set [skipWifi] when the two signatures are known to have come from the
     * same underlying WiFi scan. Android throttles scans to roughly one per
     * 30 s, so samples taken half a metre apart routinely carry byte-identical
     * WiFi readings; counting that as evidence they are the same place would
     * be measuring the throttle, not the building.
     */
    fun signalDistance(
        wifiA: Map<String, Int>,
        bleA: Map<String, Int>,
        magneticA: Float?,
        wifiB: Map<String, Int>,
        bleB: Map<String, Int>,
        magneticB: Float?,
        skipWifi: Boolean = false,
        missingAtFloor: Boolean = true,
    ): Double? {
        var weighted = 0.0
        var totalWeight = 0.0

        if (!skipWifi) {
            rssiMapDistance(wifiA, wifiB, missingAtFloor)?.let {
                weighted += RSSI_WEIGHT * it
                totalWeight += RSSI_WEIGHT
            }
        }
        rssiMapDistance(bleA, bleB, missingAtFloor)?.let {
            weighted += RSSI_WEIGHT * it
            totalWeight += RSSI_WEIGHT
        }
        if (magneticA != null && magneticB != null) {
            weighted += MAGNETIC_WEIGHT * abs(magneticA - magneticB)
            totalWeight += MAGNETIC_WEIGHT
        }

        return if (totalWeight == 0.0) null else weighted / totalWeight
    }

    /**
     * RMS RSSI difference over the union of keys seen in either map, or null
     * when neither side saw anything and there is nothing to compare.
     *
     * How an absent landmark is scored decides whether this metric works at
     * all in a busy building. With [missingAtFloor], a key present on only one
     * side is compared against [DETECTION_FLOOR_DBM] — what "not seen" actually
     * means — so a beacon that was at -50 dBm and vanished counts as a 45 dB
     * difference, while one that was at -92 dBm and vanished counts as 3 dB.
     *
     * The alternative, a flat [MISSING_SIGNAL_PENALTY_DB] for every absence,
     * scores those two identically, and that is ruinous in a home with dozens
     * of visible devices. Most of them sit near the detection floor and wink in
     * and out between scans for reasons that have nothing to do with where the
     * phone is; each flicker then injects a large, *distance-independent* term.
     * With enough devices those terms swamp the few dB of real change across a
     * metre, which flattens the resolution curve at short range while leaving
     * it intact at long range — exactly the shape a real survey produced here.
     */
    private fun rssiMapDistance(
        live: Map<String, Int>,
        stored: Map<String, Int>,
        missingAtFloor: Boolean = true,
    ): Double? {
        val keys = live.keys + stored.keys
        if (keys.isEmpty()) return null
        val sumSquares = keys.sumOf { key ->
            val liveVal = live[key]
            val storedVal = stored[key]
            val diff = when {
                liveVal != null && storedVal != null -> (liveVal - storedVal).toDouble()
                !missingAtFloor -> MISSING_SIGNAL_PENALTY_DB
                // Present on exactly one side: compare the value that was seen
                // against the floor it must have fallen below on the other.
                else -> (liveVal ?: storedVal)!!.toDouble() - DETECTION_FLOOR_DBM
            }
            diff * diff
        }
        return sqrt(sumSquares / keys.size)
    }
}
