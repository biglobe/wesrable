package com.wesrable.positioning.positioning

import com.wesrable.positioning.model.Anchor
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.LoopClosure
import com.wesrable.positioning.model.PositionEstimate
import com.wesrable.positioning.model.PositionSource
import com.wesrable.positioning.model.RelocalizationState
import com.wesrable.positioning.model.RoomAnchor
import com.wesrable.positioning.model.RoomEstimate
import com.wesrable.positioning.model.StoredMap
import com.wesrable.positioning.model.SurveyPoint
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
    private val roomAnchorMap: RoomAnchorMap = RoomAnchorMap(),
    private val magneticSequences: MagneticSequenceMatcher = MagneticSequenceMatcher(),
    private val survey: SurveyTracker = SurveyTracker(),
) {

    /**
     * Whether to also close loops on magnetic-field sequences. Off by
     * default, and deliberately so: measured against simulated walks it is a
     * large win in a steel-framed building when the same line is retraced
     * closely, and a mild loss otherwise — and which of those a given home is
     * cannot be known from here. See the README.
     */
    var magneticClosureEnabled: Boolean = false

    /** Loops closed on a magnetic match rather than an RSSI one. */
    var magneticClosureCount: Int = 0
        private set

    private var storedMap: StoredMap = StoredMap()
    private var relocalizer: Relocalizer = Relocalizer(emptyList())

    /** How far this session has got towards placing itself in the stored map. */
    var relocalizationState: RelocalizationState = RelocalizationState.NO_MAP
        private set

    /** Once located, roughly how well — the spread of the agreeing estimates. */
    var relocalizationUncertaintyMeters: Double? = null
        private set

    /** The map from previous sessions, for drawing beneath the live trail. */
    val storedTrail: List<Pair<Double, Double>> get() = storedMap.trail

    /**
     * Adopts a map recorded in earlier sessions. Until the session recognises
     * where in it the walker is standing, the two are simply unrelated: the
     * stored map is drawn and searched, but nothing of this session is added
     * to it.
     */
    fun loadMap(map: StoredMap) {
        storedMap = map
        relocalizer = Relocalizer(map.waypoints)
        relocalizationState =
            if (map.isEmpty) RelocalizationState.NO_MAP else RelocalizationState.SEARCHING
    }

    /**
     * The map to persist: the stored one plus this session's contribution,
     * but only once the two are known to share a frame. An unlocated session
     * has coordinates relative to an origin nobody can find again, so merging
     * it would smear the map rather than extend it.
     */
    fun exportMap(): StoredMap = when (relocalizationState) {
        RelocalizationState.SEARCHING -> storedMap
        else -> StoredMap(
            waypoints = storedMap.waypoints + loopClosure.waypointsForMap(),
            trail = storedMap.trail + deadReckoning.trail,
            roomAnchors = mergeRoomAnchors(storedMap.roomAnchors, roomAnchorMap.anchors),
        )
    }

    private fun mergeRoomAnchors(
        stored: List<RoomAnchor>,
        live: List<RoomAnchor>,
    ): List<RoomAnchor> {
        // A room seen again this session supersedes the remembered one: it was
        // just observed, where the stored copy may predate furniture moving.
        val byLabel = stored.associateBy { it.label }.toMutableMap()
        live.forEach { byLabel[it.label] = it }
        return byLabel.values.toList()
    }

    /** Total footsteps counted since the engine was created. */
    val stepCount: Int get() = deadReckoning.totalSteps

    /**
     * Distance the trail believes was walked. Shown in the UI beside the step
     * count because the two together are what diagnose a wrong-sized trail:
     * against a known walk, a distance that is too long with a plausible step
     * count means the stride estimate is wrong, and a distance that is too
     * long with an inflated step count means steps are being invented. From a
     * drawing alone the two are indistinguishable.
     */
    val pathLengthMeters: Double get() = deadReckoning.pathLengthMeters

    /** Every (east, north) position visited so far — the walked path. */
    val trail: List<Pair<Double, Double>> get() = deadReckoning.trail

    /** Where dead reckoning believes the walker is standing right now. */
    fun currentPosition(): PositionEstimate = deadReckoning.currentPosition()

    /** Loops closed so far, and by how much the last one shifted the trail. */
    var closureCount: Int = 0
        private set
    var lastClosureDriftMeters: Double? = null
        private set

    /** Where each loop was closed, for marking on the map. */
    val closurePoints: List<Pair<Double, Double>> get() = loopClosure.closurePoints

    /** The user's labeled rooms, placed on the trail wherever they answered. */
    val roomAnchors: List<RoomAnchor> get() = roomAnchorMap.anchors

    /**
     * Whether to capture a dense survey sample every half-metre walked. Off by
     * default: it is a measurement exercise the user starts deliberately, not
     * something to run behind their back.
     */
    var surveying: Boolean = false

    /** Every survey sample, from this walk and from remembered ones. */
    val surveyPoints: List<SurveyPoint> get() = survey.points

    val sessionSurveyPointCount: Int get() = survey.sessionPointCount

    fun loadSurvey(points: List<SurveyPoint>) = survey.load(points)

    fun clearSurvey() = survey.clear()

    /**
     * The survey to persist. An unlocated session's coordinates are relative
     * to an origin nothing can find again, so its samples would be scattered
     * into the stored survey at meaningless positions and quietly wreck every
     * figure the report produces — the same reason [exportMap] holds back.
     */
    fun exportSurvey(): List<SurveyPoint> = when (relocalizationState) {
        RelocalizationState.SEARCHING -> survey.storedPoints
        else -> survey.points
    }

    /**
     * Marks the exact spot a fingerprint was just recorded at. This beats
     * anything [noteRoomMatch] can infer, since the position is simply known
     * rather than derived from a noisy signal match.
     */
    fun markRecordedRoom(label: String) {
        val position = deadReckoning.currentPosition()
        roomAnchorMap.markRecorded(
            label = label,
            xMeters = position.xMeters,
            yMeters = position.yMeters,
            pathLengthMeters = deadReckoning.pathLengthMeters,
        )
    }

    fun forgetRoom(label: String) = roomAnchorMap.forget(label)

    fun renameRoom(oldLabel: String, newLabel: String) = roomAnchorMap.rename(oldLabel, newLabel)

    /**
     * Offers the current room match so the label can be pinned to the map at
     * wherever the walker was standing when it matched.
     */
    fun noteRoomMatch(estimate: RoomEstimate) {
        val position = deadReckoning.currentPosition()
        roomAnchorMap.note(
            label = estimate.label,
            confidence = estimate.confidence,
            nearestDistanceDb = estimate.nearestDistanceDb,
            xMeters = position.xMeters,
            yMeters = position.yMeters,
            pathLengthMeters = deadReckoning.pathLengthMeters,
        )
    }

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

        if (relocalizationState == RelocalizationState.SEARCHING) {
            relocalizer.observe(
                wifiRssi = wifiRssi,
                bleRssi = bleRssi,
                magneticMagnitudeUt = magneticMagnitudeUt,
                sessionEast = position.xMeters,
                sessionNorth = position.yMeters,
                pathLengthMeters = deadReckoning.pathLengthMeters,
            )?.let { fix ->
                // The shape walked so far was right all along; only its place
                // in the world was unknown. Slide it there bodily.
                deadReckoning.translate(fix.offsetEastMeters, fix.offsetNorthMeters)
                loopClosure.translate(fix.offsetEastMeters, fix.offsetNorthMeters)
                roomAnchorMap.translate(fix.offsetEastMeters, fix.offsetNorthMeters)
                magneticSequences.translate(fix.offsetEastMeters, fix.offsetNorthMeters)
                survey.translate(fix.offsetEastMeters, fix.offsetNorthMeters)
                relocalizationState = RelocalizationState.LOCATED
                relocalizationUncertaintyMeters = fix.uncertaintyMeters
            }
        }

        // The sharper of the two constraints first, when the user has turned
        // it on: a magnetic match is worth well under a meter where an RSSI
        // one is worth several, so applying it first leaves less for the
        // coarse one to find.
        if (magneticClosureEnabled) {
            // Read afresh: a rebase just above may have moved us.
            val here = deadReckoning.currentPosition()
            magneticSequences.observe(
                magnitudeUt = magneticMagnitudeUt,
                xMeters = here.xMeters,
                yMeters = here.yMeters,
                pathLengthMeters = deadReckoning.pathLengthMeters,
            )?.let { magneticClosure ->
                applyClosure(magneticClosure)
                magneticClosureCount++
            }
        }

        val current = deadReckoning.currentPosition()
        val closure = loopClosure.observe(
            xMeters = current.xMeters,
            yMeters = current.yMeters,
            pathLengthMeters = deadReckoning.pathLengthMeters,
            wifiRssi = wifiRssi,
            bleRssi = bleRssi,
            magneticMagnitudeUt = magneticMagnitudeUt,
            wifiScanGeneration = wifiScanGeneration,
        )

        if (closure != null) {
            applyClosure(closure)
            closureCount++
        }

        // Captured last, and re-reading the position rather than reusing
        // `current`: a closure applied a moment ago may have moved the walker,
        // and a survey sample filed at a coordinate the trail has already
        // abandoned would be measured against the wrong separation forever.
        if (surveying) {
            val here = deadReckoning.currentPosition()
            survey.observe(
                xMeters = here.xMeters,
                yMeters = here.yMeters,
                pathLengthMeters = deadReckoning.pathLengthMeters,
                wifiRssi = wifiRssi,
                bleRssi = bleRssi,
                magneticMagnitudeUt = magneticMagnitudeUt,
                atMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Removes a recognised drift from the trail and from every structure
     * expressed against it, so markers and stored waypoints keep pointing at
     * the part of the path they were observed at.
     */
    private fun applyClosure(closure: LoopClosure) {
        val anchor = closure.anchorPathLengthMeters
        val end = closure.currentPathLengthMeters
        val east = closure.driftEastMeters
        val north = closure.driftNorthMeters

        deadReckoning.rubberSheet(anchor, end, east, north)
        loopClosure.applyCorrection(anchor, end, east, north)
        roomAnchorMap.applyCorrection(anchor, end, east, north)
        magneticSequences.applyCorrection(anchor, end, east, north)
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
