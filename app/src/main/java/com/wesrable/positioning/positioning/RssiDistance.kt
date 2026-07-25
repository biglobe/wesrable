package com.wesrable.positioning.positioning

import kotlin.math.pow

/**
 * Converts a received-signal-strength reading into an estimated range using the
 * standard log-distance path loss model:
 *
 *   distance = 10 ^ ((txPowerAt1m - rssi) / (10 * n))
 *
 * `txPowerAt1m` is the calibrated RSSI a receiver would see 1 meter from the
 * transmitter (broadcast by beacons that support it; otherwise a typical
 * default is used). `n` is the path-loss exponent, which depends on the
 * environment (~2.0 in free space, 2.5-4.0 indoors with walls/furniture).
 *
 * This is inherently noisy (RSSI can vary several dB from multipath fading and
 * body shadowing) so treat the result as an order-of-magnitude range estimate,
 * not a precise ranging measurement — see README for higher-accuracy
 * alternatives such as WiFi RTT (802.11mc) and UWB.
 */
object RssiDistance {

    const val DEFAULT_WIFI_TX_POWER_AT_1M = -40
    const val DEFAULT_BLE_TX_POWER_AT_1M = -59
    const val DEFAULT_PATH_LOSS_EXPONENT = 2.7

    fun estimateMeters(
        rssiDbm: Int,
        txPowerAt1m: Int,
        pathLossExponent: Double = DEFAULT_PATH_LOSS_EXPONENT,
    ): Double {
        if (rssiDbm == 0) return -1.0
        val ratio = (txPowerAt1m - rssiDbm).toDouble() / (10.0 * pathLossExponent)
        return 10.0.pow(ratio)
    }
}
