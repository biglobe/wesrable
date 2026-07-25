package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.BleSignal
import com.wesrable.positioning.model.WifiSignal

@Composable
fun WifiListCard(signals: List<WifiSignal>, available: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("WiFi access points seen (passive scan, not connected)", style = MaterialTheme.typography.titleMedium)
            val lastScanAgeSeconds = signals.maxOfOrNull { it.lastSeenMillis }
                ?.let { (System.currentTimeMillis() - it) / 1000 }
            if (lastScanAgeSeconds != null) {
                Text(
                    "Last scan: ${lastScanAgeSeconds}s ago " +
                        "(Android limits scans to ~4 per 2 min, so this updates in bursts)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                !available -> Text("WiFi radio not available or disabled.")
                signals.isEmpty() -> Text("Scanning…")
                else -> signals.take(15).forEach { s ->
                    val label = if (s.ssid == "(hidden)") "(hidden) ${s.bssid}" else s.ssid
                    Text("$label  ·  ${s.rssiDbm} dBm  ·  ~${"%.1f".format(s.distanceMeters)} m")
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun BleListCard(signals: List<BleSignal>, available: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Bluetooth LE devices seen (observer scan, not paired)", style = MaterialTheme.typography.titleMedium)
            when {
                !available -> Text("Bluetooth not available or disabled.")
                signals.isEmpty() -> Text("Scanning…")
                else -> signals.take(15).forEach { s ->
                    val label = s.name ?: s.identifier
                    Text("$label  ·  ${s.type}  ·  ${s.rssiDbm} dBm  ·  ~${"%.1f".format(s.distanceMeters)} m")
                    HorizontalDivider()
                }
            }
        }
    }
}
