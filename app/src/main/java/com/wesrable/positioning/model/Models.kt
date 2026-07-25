package com.wesrable.positioning.model

/** Device attitude derived from the fused rotation-vector sensor, in degrees. */
data class Orientation(
    val azimuthDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val accuracy: Int = 0,
)

/** One passively observed WiFi access point, from a scan we never associated with. */
data class WifiSignal(
    val bssid: String,
    val ssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val distanceMeters: Double,
    val lastSeenMillis: Long,
)

enum class BleAdvertisementType { IBEACON, EDDYSTONE, GENERIC }

/** One passively observed BLE advertisement, from a scan we never connected to. */
data class BleSignal(
    val address: String,
    val name: String?,
    val type: BleAdvertisementType,
    val identifier: String,
    val rssiDbm: Int,
    val calibratedRssiAt1m: Int,
    val distanceMeters: Double,
    val lastSeenMillis: Long,
)

/** A reference point (AP or beacon) whose real-world coordinates are known via calibration. */
data class Anchor(
    val id: String,
    val xMeters: Double,
    val yMeters: Double,
)

enum class PositionSource { TRILATERATION, DEAD_RECKONING, FUSED, UNAVAILABLE }

data class PositionEstimate(
    val xMeters: Double,
    val yMeters: Double,
    val source: PositionSource,
    val confidenceRadiusMeters: Double,
)

data class BarometricReading(
    val pressureHpa: Float,
    val altitudeMeters: Float,
    val relativeFloor: Int,
)

/**
 * One labeled sample recorded during a calibration walk: the RSSI seen from
 * every visible WiFi/BLE landmark plus the ambient magnetic field strength,
 * all captured at a point the user identified by name (e.g. "Kitchen").
 * No coordinates are needed — matching is purely against this signature.
 */
data class Fingerprint(
    val label: String,
    val wifiRssi: Map<String, Int>,
    val bleRssi: Map<String, Int>,
    val magneticMagnitudeUt: Float,
    val recordedAtMillis: Long,
)

/** Result of matching a live signal snapshot against the stored fingerprint map. */
data class RoomEstimate(
    val label: String?,
    val confidence: Double,
)

/**
 * A fingerprint captured automatically along the walked trail, tagged with
 * the dead-reckoned position it was taken at. Unlike [Fingerprint] these need
 * no label and are never shown to the user — they exist so the app can
 * recognise that it has returned to a place it has already been, and correct
 * the accumulated drift between the two visits (see `LoopClosure`).
 *
 * [wifiScanGeneration] identifies which WiFi scan the RSSIs came from.
 * Android throttles scans to roughly one per 30 s, so two waypoints recorded
 * a few metres apart routinely carry *byte-identical* WiFi readings. Comparing
 * those would report a perfect match between genuinely different places, so
 * the WiFi term is skipped whenever two waypoints share a generation.
 */
data class TrailWaypoint(
    val xMeters: Double,
    val yMeters: Double,
    val pathLengthMeters: Double,
    val wifiRssi: Map<String, Int>,
    val bleRssi: Map<String, Int>,
    val magneticMagnitudeUt: Float?,
    val wifiScanGeneration: Long,
)

/**
 * A detected revisit: the walker is judged to be back at the place recorded
 * by the waypoint at [matchedIndex]. [driftEastMeters]/[driftNorthMeters] is
 * how far the dead-reckoned position has slipped between the two visits —
 * the error the trail correction removes.
 */
data class LoopClosure(
    val matchedIndex: Int,
    val anchorPathLengthMeters: Double,
    val currentPathLengthMeters: Double,
    val driftEastMeters: Double,
    val driftNorthMeters: Double,
) {
    val driftMeters: Double
        get() = kotlin.math.hypot(driftEastMeters, driftNorthMeters)
}
