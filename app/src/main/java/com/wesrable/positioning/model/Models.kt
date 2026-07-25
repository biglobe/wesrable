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
