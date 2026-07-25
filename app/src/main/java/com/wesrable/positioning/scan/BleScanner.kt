package com.wesrable.positioning.scan

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.positioning.RssiDistance
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Passively observes nearby BLE advertisements (observer role only). This
 * never calls connectGatt(): each visible device or beacon is used purely as
 * an RF landmark, identified by its advertisement payload and ranged by RSSI.
 */
class BleScanner(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

    val isAvailable: Boolean get() = bluetoothAdapter?.isEnabled == true

    fun scans(): Flow<BleSignal> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { emit(it) }
            }

            override fun onScanFailed(errorCode: Int) = Unit

            private fun emit(result: ScanResult) {
                val parsed = BleAdvertisementParser.parse(result.scanRecord, result.device.address)
                @Suppress("MissingPermission")
                val name = try {
                    result.scanRecord?.deviceName ?: result.device.name
                } catch (e: SecurityException) {
                    null
                }
                trySend(
                    BleSignal(
                        address = result.device.address,
                        name = name,
                        type = parsed.type,
                        identifier = parsed.identifier,
                        rssiDbm = result.rssi,
                        calibratedRssiAt1m = parsed.calibratedRssiAt1m,
                        distanceMeters = RssiDistance.estimateMeters(
                            rssiDbm = result.rssi,
                            txPowerAt1m = parsed.calibratedRssiAt1m,
                        ),
                        lastSeenMillis = System.currentTimeMillis(),
                    )
                )
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        @Suppress("MissingPermission")
        try {
            // No filters: passively observe every advertisement in range,
            // never initiating a connection.
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            // Missing runtime permission; caller is expected to have
            // requested it before starting the flow.
        }

        awaitClose {
            @Suppress("MissingPermission")
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                // Adapter may already be off.
            }
        }
    }
}
