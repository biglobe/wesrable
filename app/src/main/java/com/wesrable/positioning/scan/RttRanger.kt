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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    /** Whether it is switched on right now (the user can disable it system-wide). */
    val isAvailable: Boolean
        @RequiresApi(Build.VERSION_CODES.P)
        get() = rttManager?.isAvailable == true

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
                                trySend(results.mapNotNull { it.toMeasurement() })
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
    }
}
