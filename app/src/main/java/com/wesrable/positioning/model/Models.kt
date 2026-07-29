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
) {
    /**
     * Whether this is a distance at all, rather than a status code wearing one.
     *
     * A ranging result can carry `STATUS_SUCCESS` and still contain no real
     * measurement: access points submitted through the non-802.11mc path
     * routinely return a large fixed placeholder, and a real survey here saw
     * six of them report exactly 15000 m each while sitting between -32 and
     * -73 dBm. Treating that as a fix would have sent someone off to measure
     * router positions for a trilateration that could never work, so the
     * number is checked against physics before it is believed: an access point
     * audible indoors is not kilometres away, and a burst that never completed
     * has nothing to report.
     */
    val isPlausibleDistance: Boolean
        get() = successfulMeasurements > 0 &&
            distanceMeters > 0.0 &&
            distanceMeters <= MAX_PLAUSIBLE_DISTANCE_METERS

    companion object {
        /**
         * Beyond this, indoors, the reading is a sentinel rather than a
         * distance. Generous on purpose — a large building's far corner is
         * tens of metres, never hundreds.
         */
        const val MAX_PLAUSIBLE_DISTANCE_METERS = 150.0
    }
}

/** What happened when one access point was actually asked to range. */
enum class RttProbeStatus {
    /** It answered with a real, physically possible distance. */
    RANGED,

    /**
     * It answered, but the distance it returned is not a distance — typically
     * a large fixed placeholder from an access point that is not a true
     * 802.11mc responder. Counted apart from [RANGED] because it looks like
     * success everywhere except in the number itself.
     */
    NO_DISTANCE,

    /** It replied that it cannot do 802.11mc ranging. A definite no. */
    NOT_SUPPORTED,

    /**
     * The request failed without a clear answer — out of range mid-request,
     * busy, or the radio declined. Not the same as a refusal, and worth
     * retrying before concluding anything.
     */
    FAILED,
}

/**
 * The result of asking one access point to range, rather than of reading what
 * it advertises.
 *
 * The distinction is the point. An access point announces 802.11mc support in a
 * capability bit, and the app previously trusted that bit alone — but the bit is
 * frequently unset on hardware that will happily answer a ranging request, and
 * occasionally set on hardware that will not. The only reliable way to find out
 * is to ask.
 */
data class RttProbe(
    val bssid: String,
    val ssid: String,
    val rssiDbm: Int,
    /** What the access point claimed in its beacon, before being asked. */
    val advertisedResponder: Boolean,
    val status: RttProbeStatus,
    /** Whatever came back, believable or not — shown so a placeholder is visible. */
    val distanceMeters: Double? = null,
    val standardDeviationMeters: Double? = null,
    val successfulMeasurements: Int = 0,
    val attemptedMeasurements: Int = 0,
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
 * One sample of a *dense survey*: the same signal signature a [Fingerprint]
 * carries, but recorded automatically every half-metre along the walk and
 * tagged with the position it was taken at, instead of being named by hand
 * once per room.
 *
 * The point of recording them this densely is measurement rather than
 * matching. Comparing every pair of these against the physical distance
 * between them is what turns "how precisely can this house be located by
 * signal?" from an argument into a number — see `FingerprintCrossValidation`.
 *
 * [pathLengthMeters] is kept so that a loop closure can drag these along with
 * the trail they were recorded on, exactly as it does room anchors; without it
 * a correction would leave the survey pinned to coordinates the trail no
 * longer uses.
 */
data class SurveyPoint(
    val xMeters: Double,
    val yMeters: Double,
    val pathLengthMeters: Double,
    val wifiRssi: Map<String, Int>,
    val bleRssi: Map<String, Int>,
    val magneticMagnitudeUt: Float?,
    val recordedAtMillis: Long,
)

/**
 * One printed marker recognised in a camera frame.
 *
 * [sizePixels] is the shortest side of the marker as it appeared, which is the
 * only distance cue available without knowing the camera's focal length: a
 * marker filling the frame is at arm's length, one 30 px across is at the far
 * end of the room. That is enough for the job here — picking out the cabinet
 * being *looked at* means preferring the nearest marker, not measuring it.
 */
data class DetectedMarker(
    val id: Int,
    val centerXPixels: Double,
    val centerYPixels: Double,
    val sizePixels: Double,
    /** 90-degree steps between the marker as printed and as seen. */
    val rotationSteps: Int,
    /** Payload bits that needed correcting; 0 is a clean read. */
    val bitErrors: Int,
)

/**
 * A marker the user has given a name to, pinned where they stood when they
 * named it.
 *
 * This is the join between the two halves of the problem. Passive signals get
 * the walker to the right part of the right room and no closer — measured, not
 * assumed — while the marker says exactly which of several identical cabinets
 * is in view. Neither answers the question alone.
 */
data class MarkerLabel(
    val markerId: Int,
    val label: String,
    val xMeters: Double,
    val yMeters: Double,
    val seenCount: Int,
    val lastSeenMillis: Long,
)

/** How far a survey report got before running out of evidence. */
enum class SurveyReportStatus {
    /** Too few samples to say anything. Keep walking. */
    NOT_ENOUGH_POINTS,

    /**
     * Plenty of samples, but no place was visited twice far enough apart in
     * time. Without that there is no measurement of what "the same place twice"
     * even looks like, so there is nothing to compare a different place against.
     */
    NOT_ENOUGH_REVISITS,

    READY,
}

/**
 * One band of physical separation in the measured resolution curve:
 * of all sample pairs that were [lowMeters]-[highMeters] apart, what fraction
 * looked different enough in signal to be told apart.
 */
data class ResolutionBin(
    val lowMeters: Double,
    val highMeters: Double,
    val pairCount: Int,
    val distinguishedFraction: Double,
)

/**
 * What the survey actually had to work with.
 *
 * Without this, a weak resolution curve has two explanations that point in
 * opposite directions: the building genuinely has few distinct signals, or the
 * app is failing to use the ones it has. The first means stop walking and buy
 * hardware; the second means fix the app. Counting what went in separates them.
 *
 * [pairsWifiStale] is the one to watch. Android throttles WiFi scans to roughly
 * one per 30 s, so samples taken half a metre apart routinely carry
 * byte-identical readings, and those are discarded rather than believed. If
 * nearly every pair is stale, the survey is not really using WiFi at all,
 * whatever [distinctWifiAps] says — it is running on BLE and the magnetometer.
 */
data class SurveySignalCensus(
    val distinctWifiAps: Int = 0,
    val distinctBleDevices: Int = 0,
    val medianWifiPerSample: Int = 0,
    val medianBlePerSample: Int = 0,
    val samplesWithMagnetic: Int = 0,
    val pairsUsingWifi: Int = 0,
    val pairsUsingBle: Int = 0,
    val pairsUsingMagnetic: Int = 0,
    val pairsWifiStale: Int = 0,
    /** Share of WiFi landmarks in a typical pair that only one side saw. */
    val unmatchedWifiFraction: Double = 0.0,
    /** The same for BLE, where transient advertisers make it far higher. */
    val unmatchedBleFraction: Double = 0.0,
)

/**
 * What a dense survey of this particular building actually supports —
 * measured, not assumed.
 *
 * [noiseFloorDb] is the signal distance that separates the two questions. It is
 * taken from pairs of samples recorded at effectively the same spot on
 * *different passes*, so it is the size of the difference that mere noise,
 * body position and time of day produce. Any pair that differs by more than
 * that is counted as distinguished — which by construction makes the
 * same-place band itself read about 10%, and that is the useful baseline: a
 * separation band scoring near 10% is indistinguishable from standing still.
 */
data class SurveyReport(
    val status: SurveyReportStatus,
    val pointCount: Int = 0,
    val comparedPairCount: Int = 0,
    val revisitPairCount: Int = 0,
    val noiseFloorDb: Double = 0.0,
    /** Leave-one-out: hold out each sample, locate it from the others. */
    val medianErrorMeters: Double? = null,
    val p90ErrorMeters: Double? = null,
    val bins: List<ResolutionBin> = emptyList(),
    /**
     * Narrowest separation this survey reliably resolves — the lower edge of
     * the tightest band that, along with every wider band, is distinguished at
     * least 90% of the time. Null when even the widest band never gets there.
     */
    val resolvedAtMeters: Double? = null,
    /** Diagonal of the surveyed area, as a sanity check on coverage. */
    val spanMeters: Double = 0.0,
    /** What the survey had to work with — reported whatever the status. */
    val census: SurveySignalCensus = SurveySignalCensus(),
    /**
     * How much more alike the pairs dead reckoning calls "the same place" are
     * than a typical pair: the median signal distance of revisits over the
     * median across all pairs.
     *
     * This checks the ruler rather than the thing being measured. If the
     * positions are sound, samples the app believes are co-located really are,
     * and should be markedly more alike in signal than two points picked at
     * random — a ratio well below 1. A ratio near 1 says the pairs being called
     * revisits are no more alike than anything else, which means they are not
     * revisits: position error is large enough that the separations every band
     * in the curve is built from are fiction. The signals are then being scored
     * against a corrupted axis, and no amount of extra walking helps.
     */
    val revisitSignalRatio: Double = 0.0,
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
