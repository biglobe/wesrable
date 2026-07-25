package com.wesrable.positioning.scan

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import com.wesrable.positioning.model.BleAdvertisementType
import com.wesrable.positioning.positioning.RssiDistance
import java.util.UUID

data class ParsedAdvertisement(
    val type: BleAdvertisementType,
    val identifier: String,
    val calibratedRssiAt1m: Int,
)

/**
 * Decodes the two most common "positioning beacon" BLE advertisement formats
 * directly from the raw advertisement payload — this is pure client-side
 * parsing of broadcast bytes, not a GATT connection.
 */
object BleAdvertisementParser {

    private const val APPLE_COMPANY_ID = 0x004C
    private val EDDYSTONE_SERVICE_UUID: ParcelUuid =
        ParcelUuid(UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"))

    fun parse(record: ScanRecord?, fallbackAddress: String): ParsedAdvertisement {
        record ?: return generic(fallbackAddress)

        parseIBeacon(record)?.let { return it }
        parseEddystone(record)?.let { return it }
        return generic(fallbackAddress)
    }

    private fun parseIBeacon(record: ScanRecord): ParsedAdvertisement? {
        val data = record.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return null
        // iBeacon subframe: 0x02 0x15 <uuid:16> <major:2> <minor:2> <measuredPower:1>
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null

        val uuidBytes = data.copyOfRange(2, 18)
        val uuid = bytesToUuidString(uuidBytes)
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val measuredPower = data[22].toInt()

        return ParsedAdvertisement(
            type = BleAdvertisementType.IBEACON,
            identifier = "$uuid-$major-$minor",
            calibratedRssiAt1m = measuredPower,
        )
    }

    private fun parseEddystone(record: ScanRecord): ParsedAdvertisement? {
        val data = record.getServiceData(EDDYSTONE_SERVICE_UUID) ?: return null
        if (data.isEmpty()) return null
        val frameType = data[0].toInt() and 0xFF

        // Eddystone-UID: [frameType, txPowerAt0m, namespace(10), instance(6), rfu(2)]
        if (frameType == 0x00 && data.size >= 18) {
            val txPowerAt0m = data[1].toInt()
            val namespace = data.copyOfRange(2, 12).joinToString("") { "%02x".format(it) }
            val instance = data.copyOfRange(12, 18).joinToString("") { "%02x".format(it) }
            return ParsedAdvertisement(
                type = BleAdvertisementType.EDDYSTONE,
                identifier = "$namespace-$instance",
                // Eddystone calibrates Tx power at 0 m; convert to an
                // approximate 1 m reference using free-space path loss (~-41 dB/m decade).
                calibratedRssiAt1m = txPowerAt0m - 41,
            )
        }
        return null
    }

    private fun generic(address: String) = ParsedAdvertisement(
        type = BleAdvertisementType.GENERIC,
        identifier = address,
        calibratedRssiAt1m = RssiDistance.DEFAULT_BLE_TX_POWER_AT_1M,
    )

    private fun bytesToUuidString(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
