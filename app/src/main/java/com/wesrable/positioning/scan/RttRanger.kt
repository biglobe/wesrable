package com.wesrable.positioning.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.wesrable.positioning.model.RttMeasurement
import com.wesrable.positioning.model.RttProbe
import com.wesrable.positioning.model.RttProbeStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor

/**
 * Measures true distance to WiFi access points by time of flight (IEEE
 * 802.11mc, "WiFi RTT"), rather than inferring it from signal strength.
 *
 * This is the difference between the positioning in this app and the
 * positioning in a shopping mall. RSSI falls off logarithmically and is
 * mangled by walls, bodies and multipath, so converting it to metres is
 * guesswork good to several metres at best — measured earlier in this
 * project, standing still and standing 12 m away produce overlapping
 * readings. RTT instead times a round trip of the radio signal and reports
 * distance directly, typically to 1-2 m, with a standard deviation the AP
 * itself estimates.
 *
 * The catch is support on both ends. The phone needs `FEATURE_WIFI_RTT`
 * (API 28+), and each access point has to be an 802.11mc responder — many
 * consumer routers are not, though Google/Nest WiFi and most mesh systems
 * built since about 2019 are. Nothing here associates with any network: RTT
 * is a management-frame exchange, so it stays within the app's
 * scan-only-never-connect rule.
 */
class RttRanger(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val rttManager: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        ) {
            context.applicationContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE)
                as? WifiRttManager
        } else {
            null
        }

    /** Whether this phone can do RTT ranging at all. */
    val isSupportedByDevice: Boolean get() = rttManager != null

    /**
     * Whether this phone has an ultra-wideband radio.
     *
     * UWB is the only radio technology that ranges to 10-30 cm rather than
     * metres, which is the difference between knowing the room and knowing
     * the cabinet. It needs a UWB tag or anchor at the other end, so it is
     * not passive — but it is the one RF answer to sub-metre indoors, and
     * whether the phone can do it at all decides if that door is even open.
     */
    val isUwbSupportedByDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature("android.hardware.uwb")

    /**
     * Total access points visible, whether or not they answer ranging.
     *
     * Reported alongside the responder count so that "none of the twelve
     * routers here support it" can be told apart from "the scan returned
     * nothing at all" — the card would otherwise show the same message for a
     * building without the feature and for a permissions or throttling
     * failure on our side.
     */
    fun accessPointsInRange(): Int =
        runCatching { wifiManager?.scanResults?.size }.getOrNull() ?: 0

    /**
     * What the radio itself claims it can do.
     *
     * `getRttCharacteristics()` returns a bundle of boolean capability flags —
     * one-sided RTT, LCI/LCR location reporting, and from API 35 whether this
     * phone can act as an 802.11az non-trigger-based initiator. The keys are
     * read and reported verbatim rather than being looked up by constant,
     * because the set grows with each platform release and a hardcoded list
     * would silently omit whatever was added last.
     *
     * Reached by reflection: the app compiles against API 34, and naming the
     * newer symbols directly would not build while querying them at runtime
     * works perfectly well on the devices that have them.
     */
    fun rttCharacteristics(): Map<String, Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyMap()
        val manager = rttManager ?: return emptyMap()
        val bundle = runCatching {
            WifiRttManager::class.java.getMethod("getRttCharacteristics").invoke(manager)
                as? android.os.Bundle
        }.getOrNull() ?: return emptyMap()

        return buildMap {
            runCatching { bundle.keySet() }.getOrNull().orEmpty().forEach { key ->
                val value = runCatching { @Suppress("DEPRECATION") bundle.get(key) }.getOrNull()
                if (value is Boolean) put(key, value)
            }
        }
    }

    /**
     * Whether this phone can start an 802.11az ranging exchange.
     *
     * 802.11az is the successor to 802.11mc: better accuracy, and designed to
     * scale to many clients at once. It is not a separate call — the platform
     * negotiates it inside an ordinary ranging request when both ends support
     * it — so what matters is knowing whether the phone is capable, and then
     * which standard each measurement actually came from.
     *
     * Matched on the key rather than a constant for the reason above.
     */
    val isAzInitiatorSupported: Boolean
        get() = rttCharacteristics().any { (key, enabled) ->
            // Both terms, not just "NTB": a phone may be able to *answer* an
            // 802.11az exchange without being able to start one, and the
            // characteristics bundle exposes those separately. Matching NTB
            // alone would read an ntb_responder flag as initiator support.
            enabled && key.contains("NTB", ignoreCase = true) &&
                key.contains("INITIATOR", ignoreCase = true)
        }

    /** Whether it is switched on right now (the user can disable it system-wide). */
    val isAvailable: Boolean
        @RequiresApi(Build.VERSION_CODES.P)
        get() = rttManager?.isAvailable == true

    /**
     * Asks every visible access point to range, instead of only the ones that
     * advertise the capability.
     *
     * This exists because [respondersInRange] answers the wrong question. It
     * filters on `is80211mcResponder()`, a bit the access point sets in its
     * beacon, and that bit is unset on a good deal of hardware that will
     * nonetheless answer a ranging request — some vendors never set it, some
     * only set it on the 5 GHz radio, and Android does not always surface it
     * from a passive scan. A building can therefore report "no responders"
     * while containing a router that would have ranged perfectly well.
     *
     * From API 31 the platform provides exactly the call needed for this:
     * `addNon80211mcCapableAccessPoint`, which submits an access point that
     * did not advertise support. Below 31 only advertised responders can be
     * asked, so the probe degrades to the old behaviour.
     *
     * Strongest first, since a distant access point that fails tells us far
     * less than a near one that does.
     */
    suspend fun probeAll(maxAccessPoints: Int = DEFAULT_PROBE_LIMIT): List<RttProbe> {
        val manager = rttManager
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        if (!hasLocationPermission()) return emptyList()

        val visible = runCatching { wifiManager?.scanResults }.getOrNull().orEmpty()
            .sortedByDescending { it.level }
            .take(maxAccessPoints)
        if (visible.isEmpty()) return emptyList()

        unsupported.clear()
        val results = mutableListOf<RttProbe>()
        // The radio caps how many peers one request may carry, so a busy
        // building has to be probed in batches.
        visible.chunked(RangingRequest.getMaxPeers().coerceAtLeast(1)).forEach { batch ->
            results += probeBatch(manager, batch)
        }
        // A request that fails takes its whole batch down with it, so an access
        // point that would have answered can be recorded as silent purely for
        // travelling in bad company. Anything that gave no answer is asked
        // again on its own, where nothing else can spoil it. Refusals are not
        // retried — those were a real reply.
        val byBssid = results.associateBy { it.bssid }.toMutableMap()
        results.filter { it.status == RttProbeStatus.FAILED }
            .sortedByDescending { it.rssiDbm }
            .take(MAX_SOLO_RETRIES)
            .forEach { silent ->
                val scan = visible.firstOrNull { it.BSSID == silent.bssid } ?: return@forEach
                val retried = probeBatch(manager, listOf(scan)).firstOrNull() ?: return@forEach
                if (retried.status != RttProbeStatus.FAILED) byBssid[silent.bssid] = retried
            }

        return byBssid.values.sortedWith(
            compareBy({ it.status.ordinal }, { -it.rssiDbm })
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun probeBatch(
        manager: WifiRttManager,
        batch: List<android.net.wifi.ScanResult>,
    ): List<RttProbe> {
        val advertised = batch.associate { scan ->
            scan.BSSID to runCatching { scan.is80211mcResponder }.getOrDefault(false)
        }
        val advertisedAz = batch.associate { scan -> scan.BSSID to scan.advertisesAz() }

        var askable = 0
        val request = runCatching {
            RangingRequest.Builder().apply {
                batch.forEach { scan ->
                    val isResponder = advertised[scan.BSSID] == true
                    when {
                        isResponder -> {
                            addAccessPoint(scan)
                            askable++
                        }
                        // Below API 31 there is no way to submit an access
                        // point that did not advertise support, so on those
                        // devices the beacon bit really is the last word.
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                            addNon80211mcCapableAccessPoint(scan)
                            askable++
                        }
                    }
                }
            }.build()
        }.getOrNull()

        // Report the batch either way. Returning nothing would quietly shrink
        // the table and make a phone too old to probe look like a building
        // with no access points in it.
        val ranged = if (request == null || askable == 0) {
            emptyMap()
        } else {
            rangeOnce(manager, request).associateBy { it.bssid }
        }

        return batch.mapNotNull { scan ->
            val bssid = scan.BSSID ?: return@mapNotNull null
            val measurement = ranged[bssid]
            RttProbe(
                bssid = bssid,
                ssid = scan.SSID.orEmpty().ifBlank { "(hidden)" },
                rssiDbm = scan.level,
                frequencyMhz = scan.frequency,
                advertisedResponder = advertised[bssid] == true,
                advertisedAzResponder = advertisedAz[bssid] == true,
                rangedVia80211az = measurement?.is80211az == true,
                status = when {
                    // A result can report success and still contain no
                    // measurement, so the number is checked rather than the
                    // status. See RttMeasurement.isPlausibleDistance.
                    measurement?.isPlausibleDistance == true -> RttProbeStatus.RANGED
                    measurement != null -> RttProbeStatus.NO_DISTANCE
                    unsupported.contains(bssid) -> RttProbeStatus.NOT_SUPPORTED
                    else -> RttProbeStatus.FAILED
                },
                distanceMeters = measurement?.distanceMeters,
                standardDeviationMeters = measurement?.standardDeviationMeters,
                successfulMeasurements = measurement?.successfulMeasurements ?: 0,
                attemptedMeasurements = measurement?.attemptedMeasurements ?: 0,
            )
        }
    }

    /** BSSIDs that explicitly answered "I cannot do this", from the last probe. */
    private val unsupported = mutableSetOf<String>()

    /** One ranging request, bridged from its callback to a suspending call. */
    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun rangeOnce(
        manager: WifiRttManager,
        request: RangingRequest,
    ): List<RttMeasurement> = suspendCancellableCoroutine { continuation ->
        val executor = Executor { it.run() }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val callback = object : RangingResultCallback() {
            override fun onRangingResults(results: List<RangingResult>) {
                if (!finished.compareAndSet(false, true)) return
                results.forEach { result ->
                    val bssid = runCatching { result.macAddress?.toString() }.getOrNull()
                    // Distinguishing a refusal from a failure is the whole
                    // value of the probe, so record it explicitly.
                    if (bssid != null &&
                        result.status == RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC
                    ) {
                        unsupported.add(bssid)
                    }
                }
                continuation.resumeWith(Result.success(results.mapNotNull { it.toMeasurement() }))
            }

            override fun onRangingFailure(code: Int) {
                if (!finished.compareAndSet(false, true)) return
                continuation.resumeWith(Result.success(emptyList()))
            }
        }

        runCatching { manager.startRanging(request, executor, callback) }.onFailure {
            if (finished.compareAndSet(false, true)) {
                continuation.resumeWith(Result.success(emptyList()))
            }
        }
    }

    /**
     * Access points in range that advertise 802.11mc support. Counting these
     * separately from the total matters: an empty list here means the
     * technique is unavailable in this building regardless of the phone,
     * which is a very different problem from the phone lacking the feature.
     */
    fun respondersInRange(): List<android.net.wifi.ScanResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        if (!hasLocationPermission()) return emptyList()
        val results = runCatching { wifiManager?.scanResults }.getOrNull() ?: return emptyList()
        return results.filter { runCatching { it.is80211mcResponder }.getOrDefault(false) }
    }

    /**
     * Ranges every 802.11mc responder in sight, repeatedly.
     *
     * Emits an empty list rather than failing when nothing can be ranged, so
     * the UI can distinguish "no responders here" from "never ran".
     */
    fun ranges(periodMillis: Long = DEFAULT_PERIOD_MILLIS): Flow<List<RttMeasurement>> =
        callbackFlow {
            val manager = rttManager
            if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                trySend(emptyList())
                awaitClose { }
                return@callbackFlow
            }

            val executor = Executor { it.run() }

            while (true) {
                val responders = respondersInRange().take(RangingRequest.getMaxPeers())
                if (responders.isEmpty() || !manager.isAvailable || !hasLocationPermission()) {
                    trySend(emptyList())
                    delay(periodMillis)
                    continue
                }

                val request = RangingRequest.Builder()
                    .addAccessPoints(responders)
                    .build()

                runCatching {
                    manager.startRanging(
                        request,
                        executor,
                        object : RangingResultCallback() {
                            override fun onRangingResults(results: List<RangingResult>) {
                                // Same guard as the probe: a success status is
                                // not a promise that the distance is real.
                                trySend(
                                    results.mapNotNull { it.toMeasurement() }
                                        .filter { it.isPlausibleDistance }
                                )
                            }

                            override fun onRangingFailure(code: Int) {
                                trySend(emptyList())
                            }
                        },
                    )
                }

                delay(periodMillis)
            }
        }

    /**
     * Whether this access point advertises 802.11az non-trigger-based ranging.
     * Advisory only, exactly as the 802.11mc flag is — the probe asks anyway.
     */
    private fun android.net.wifi.ScanResult.advertisesAz(): Boolean =
        runCatching {
            android.net.wifi.ScanResult::class.java
                .getMethod("is80211azNtbResponder").invoke(this) as? Boolean
        }.getOrNull() ?: false

    /**
     * Whether this measurement came from an 802.11az exchange rather than an
     * 802.11mc one. The distinction matters: az is the newer standard and
     * reports tighter, so knowing which produced a figure says how much to
     * trust it.
     */
    private fun RangingResult.cameFrom80211az(): Boolean =
        runCatching {
            RangingResult::class.java
                .getMethod("is80211azNtbMeasurement").invoke(this) as? Boolean
        }.getOrNull() ?: false

    @RequiresApi(Build.VERSION_CODES.P)
    private fun RangingResult.toMeasurement(): RttMeasurement? {
        if (status != RangingResult.STATUS_SUCCESS) return null
        val bssid = runCatching { macAddress?.toString() }.getOrNull() ?: return null
        return RttMeasurement(
            bssid = bssid,
            distanceMeters = distanceMm / 1000.0,
            // The radio's own estimate of how much to trust the figure —
            // fed straight into trilateration weighting, which RSSI could
            // never supply because it has no notion of its own error.
            standardDeviationMeters = distanceStdDevMm / 1000.0,
            rssiDbm = rssi,
            attemptedMeasurements = numAttemptedMeasurements,
            successfulMeasurements = numSuccessfulMeasurements,
            is80211az = cameFrom80211az(),
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /**
         * RTT is rate-limited by the platform and costs power, so this is
         * slower than a sensor poll but far faster than the ~30 s WiFi scan
         * throttle that hobbles RSSI fingerprinting.
         */
        const val DEFAULT_PERIOD_MILLIS = 2_000L

        /**
         * Access points to probe, strongest first. Ranging costs power and is
         * rate-limited by the platform, and an access point too weak to appear
         * in the top of the list is too weak to trilaterate from anyway.
         */
        const val DEFAULT_PROBE_LIMIT = 24

        /**
         * Silent access points re-asked one at a time. Capped because each is a
         * separate rate-limited request; the strongest are retried first, since
         * a router in the same building is what this is for.
         */
        const val MAX_SOLO_RETRIES = 10
    }
}
