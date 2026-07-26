package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.RttMeasurement

/**
 * Time-of-flight ranging, and — just as important — a plain statement of
 * whether it is available at all, since that is decided by the building's
 * routers as much as by the phone.
 */
@Composable
fun RttCard(
    measurements: List<RttMeasurement>,
    supportedByDevice: Boolean,
    respondersInRange: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("WiFi RTT ranging (802.11mc)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Measures distance by timing the radio round trip instead of guessing " +
                    "it from signal strength — metre-level rather than the several " +
                    "metres RSSI manages. Needs both a capable phone and access points " +
                    "that answer ranging requests.",
                style = MaterialTheme.typography.bodySmall,
            )

            when {
                !supportedByDevice -> Text(
                    "This phone does not support RTT — no 802.11mc radio. Ranging has to " +
                        "come from deployed beacons instead.",
                    modifier = Modifier.padding(top = 8.dp),
                )

                respondersInRange == 0 -> Text(
                    "Phone supports RTT, but no access point in range answers ranging " +
                        "requests. Most routers made before ~2019 don't; Google/Nest WiFi " +
                        "and many mesh systems do.",
                    modifier = Modifier.padding(top = 8.dp),
                )

                else -> {
                    Text(
                        "$respondersInRange access point${if (respondersInRange == 1) "" else "s"} " +
                            "in range can be ranged. Three or more with known positions is " +
                            "enough to trilaterate directly, with no dead reckoning at all.",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    measurements.forEach { measurement ->
                        Text(
                            "%s — %.2f m ±%.2f  (%d/%d bursts, %d dBm)".format(
                                measurement.bssid,
                                measurement.distanceMeters,
                                measurement.standardDeviationMeters,
                                measurement.successfulMeasurements,
                                measurement.attemptedMeasurements,
                                measurement.rssiDbm,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
