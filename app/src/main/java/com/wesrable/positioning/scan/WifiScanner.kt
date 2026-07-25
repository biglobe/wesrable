package com.wesrable.positioning.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.wesrable.positioning.model.WifiSignal
import com.wesrable.positioning.positioning.RssiDistance
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Passively observes nearby WiFi access points via [WifiManager.startScan] /
 * [WifiManager.getScanResults]. This is a *scan*, not an association: the
 * device never joins a network, requests a DHCP lease, or opens a socket.
 * We use each AP's SSID/BSSID/RSSI purely as an RF landmark for positioning.
 *
 * Android throttles foreground scan requests (roughly 4 per 2 minutes), so
 * this also opportunistically reads whatever scan results are cached even
 * when [WifiManager.startScan] itself is rate-limited or returns false.
 */
class WifiScanner(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val isAvailable: Boolean get() = wifiManager.isWifiEnabled || wifiManager.scanResults != null

    fun scans(periodMillis: Long = 15_000L): Flow<List<WifiSignal>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val success = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, true
                )
                if (!success) return
                emitCurrentResults()
            }
        }

        fun emitCurrentResults() {
            @Suppress("MissingPermission")
            val results = try {
                wifiManager.scanResults
            } catch (e: SecurityException) {
                emptyList()
            }
            val now = System.currentTimeMillis()
            val signals = results.map { r ->
                WifiSignal(
                    bssid = r.BSSID ?: "unknown",
                    ssid = r.SSID?.takeIf { it.isNotBlank() } ?: "(hidden)",
                    rssiDbm = r.level,
                    frequencyMhz = r.frequency,
                    distanceMeters = RssiDistance.estimateMeters(
                        rssiDbm = r.level,
                        txPowerAt1m = RssiDistance.DEFAULT_WIFI_TX_POWER_AT_1M,
                    ),
                    lastSeenMillis = now,
                )
            }
            trySend(signals)
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Periodically kick off a new passive scan; Android throttles this,
        // in which case we just keep surfacing the last cached results.
        var running = true
        while (running) {
            @Suppress("DEPRECATION", "MissingPermission")
            try {
                wifiManager.startScan()
            } catch (e: SecurityException) {
                // Missing runtime permission; caller is expected to have
                // requested it before starting the flow.
            }
            emitCurrentResults()
            delay(periodMillis)
        }

        awaitClose {
            running = false
            context.unregisterReceiver(receiver)
        }
    }

    companion object {
        /** WiFi RTT (IEEE 802.11mc) gives direct time-of-flight ranging without
         * ever associating with the AP — far more accurate than RSSI, but only
         * on devices/APs that support it (API 28+, FEATURE_WIFI_RTT). */
        fun isRttSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature("android.hardware.wifi.rtt")
    }
}
