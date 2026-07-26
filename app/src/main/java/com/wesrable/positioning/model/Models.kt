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

/**
 * One time-of-flight distance measurement to an access point (IEEE 802.11mc).
 *
 * Unlike an RSSI-derived distance this is an actual measurement rather than an
 * inference, and it arrives with the radio's own estimate of its error —
 * which is what lets trilateration weight good measurements above bad ones
 * instead of treating every landmark as equally trustworthy.
 */
data class RttMeasurement(
    val bssid: String,
    val distanceMeters: Double,
    val standardDeviationMeters: Double,
    val rssiDbm: Int,
    val attemptedMeasurements: Int,
    val successfulMeasurements: Int,
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

/**
 * Result of matching a live signal snapshot against the stored fingerprint map.
 *
 * [confidence] is the winning room's share of the k-nearest vote, so it says
 * how *unanimous* the match was, not how *close* it was — k-NN always returns
 * its nearest neighbour, and standing somewhere never recorded still elects a
 * winner, sometimes unanimously. [nearestDistanceDb] is the raw signal
 * distance to the closest stored sample, which is what actually says whether
 * the walker is anywhere near a mapped room at all.
 */
data class RoomEstimate(
    val label: String?,
    val confidence: Double,
    val nearestDistanceDb: Double = Double.MAX_VALUE,
)

/**
 * A labeled room pinned to trail coordinates. Rooms are recorded without any
 * coordinates — matching is pure pattern comparison — but the moment one
 * matches while walking, the dead-reckoned position supplies a location for
 * it, and averaging over repeat sightings settles it onto the middle of
 * wherever that room actually answers.
 *
 * Coordinates are relative to where the current session started, so these
 * cannot be persisted between sessions the way the fingerprints themselves
 * are; a new session starts a new origin and has to re-observe them.
 */
data class RoomAnchor(
    val label: String,
    val xMeters: Double,
    val yMeters: Double,
    val sightings: Int,
    /**
     * True when this came from where the user actually stood to record the
     * fingerprint, which is known exactly, rather than being inferred from
     * where the room later matched — which is only good to a few meters.
     */
    val isExact: Boolean,
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
 * One place in the *persistent* map: a signal signature with coordinates that
 * outlive the session that recorded them.
 *
 * The trail's axes are not arbitrary — east and north are integrated from the
 * compass, so every session already shares the same orientation and differs
 * only in where its origin happened to land. That is what makes a stored map
 * reusable at all: recovering it needs a translation, with no rotation to
 * solve for.
 */
data class MapWaypoint(
    val xMeters: Double,
    val yMeters: Double,
    val wifiRssi: Map<String, Int>,
    val bleRssi: Map<String, Int>,
    val magneticMagnitudeUt: Float?,
)

/** Everything remembered about a place between sessions. */
data class StoredMap(
    val waypoints: List<MapWaypoint> = emptyList(),
    val trail: List<Pair<Double, Double>> = emptyList(),
    val roomAnchors: List<RoomAnchor> = emptyList(),
) {
    val isEmpty: Boolean get() = waypoints.isEmpty() && trail.isEmpty()
}

/**
 * Where this session sits within the stored map: add this offset to a session
 * coordinate to get a map coordinate.
 */
data class Relocalization(
    val offsetEastMeters: Double,
    val offsetNorthMeters: Double,
    /** Spread of the agreeing estimates — roughly how far off this may be. */
    val uncertaintyMeters: Double,
)

/** How far the app has got towards placing itself in the stored map. */
enum class RelocalizationState {
    /** Nothing stored, so this session *is* the map. */
    NO_MAP,

    /** A map exists but the app hasn't recognised where in it we are yet. */
    SEARCHING,

    /** Located: session coordinates have been rebased onto the stored map. */
    LOCATED,
}

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
