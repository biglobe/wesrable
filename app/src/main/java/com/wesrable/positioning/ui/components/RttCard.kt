package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.model.RttMeasurement
import com.wesrable.positioning.model.RttProbe
import com.wesrable.positioning.model.RttProbeStatus

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
    accessPointsInRange: Int,
    uwbSupportedByDevice: Boolean,
    probes: List<RttProbe>,
    probing: Boolean,
    probeRun: Boolean,
    onProbe: () -> Unit,
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
                    if (accessPointsInRange == 0) {
                        "Phone supports RTT, but no access points are visible at all — " +
                            "so this is a scan problem here, not a verdict on the building."
                    } else {
                        "Phone supports RTT, but 0 of $accessPointsInRange visible access " +
                            "points answer ranging requests. Most routers made before ~2019 " +
                            "don't; Google/Nest WiFi and many mesh systems do. One capable " +
                            "AP would prove the path; three with known positions would " +
                            "locate you outright, with no dead reckoning involved."
                    },
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

            if (supportedByDevice) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Ask them directly", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The count above comes from a capability bit each access point sets " +
                        "in its beacon, and that bit is unreliable — plenty of routers " +
                        "will answer a ranging request without ever advertising that they " +
                        "can, and Android does not always surface the flag from a passive " +
                        "scan. This asks every access point in range outright, which is " +
                        "the only way to actually know.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onProbe, enabled = !probing, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (probing) "Probing…" else "Probe every access point")
                }
                if (probing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                ProbeResults(probes, probeRun, probing)
            }

            Text(
                if (uwbSupportedByDevice) {
                    "This phone also has ultra-wideband. UWB ranges to 10-30 cm rather " +
                        "than metres — the only radio technology that reaches cabinet " +
                        "level — but it needs a UWB tag or anchor at the other end."
                } else {
                    "No ultra-wideband radio on this phone, so the 10-30 cm ranging tier " +
                        "is closed here regardless of what is installed in the building."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The probe table. Ranged access points come first because one of them changes
 * everything: three with known positions locate the phone outright, with no
 * dead reckoning and no fingerprinting involved.
 */
@Composable
private fun ProbeResults(probes: List<RttProbe>, probeRun: Boolean, probing: Boolean) {
    if (probing) return
    if (!probeRun) return

    if (probes.isEmpty()) {
        Text(
            "No access points were visible to probe. WiFi scanning may be off, or " +
                "location permission withheld.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    val ranged = probes.count { it.status == RttProbeStatus.RANGED }
    val refused = probes.count { it.status == RttProbeStatus.NOT_SUPPORTED }
    val failed = probes.count { it.status == RttProbeStatus.FAILED }

    Text(
        when {
            ranged >= 3 -> "$ranged access points answered. That is enough to trilaterate " +
                "outright — measure where those three are and positioning stops depending " +
                "on dead reckoning at all."
            ranged > 0 -> "$ranged access point${if (ranged == 1) "" else "s"} answered. " +
                "Not enough to trilaterate on its own, but it proves the path works here — " +
                "one more capable router would make this the sharpest signal in the app."
            else -> "$refused refused outright and $failed failed to answer. No ranging " +
                "is available in this building, and now that is measured rather than " +
                "inferred from a beacon flag."
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )

    probes.take(MAX_PROBE_ROWS).forEach { probe ->
        Text(
            "%-16s %4d dBm  %-13s %s".format(
                probe.ssid.take(16),
                probe.rssiDbm,
                when (probe.status) {
                    RttProbeStatus.RANGED -> "RANGED"
                    RttProbeStatus.NOT_SUPPORTED -> "refused"
                    RttProbeStatus.FAILED -> "no answer"
                },
                probe.distanceMeters?.let { "%.2f m".format(it) }
                    ?: if (probe.advertisedResponder) "(advertised 11mc)" else "",
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }

    val advertisedButSilent = probes.count {
        it.advertisedResponder && it.status != RttProbeStatus.RANGED
    }
    val silentButRanged = probes.count {
        !it.advertisedResponder && it.status == RttProbeStatus.RANGED
    }
    if (silentButRanged > 0 || advertisedButSilent > 0) {
        Text(
            buildString {
                if (silentButRanged > 0) {
                    append("$silentButRanged access point")
                    append(if (silentButRanged == 1) " ranged" else "s ranged")
                    append(" without advertising support — exactly the case the old ")
                    append("responder count was missing. ")
                }
                if (advertisedButSilent > 0) {
                    append("$advertisedButSilent advertised support but did not answer.")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Enough to see the pattern without turning the card into a scrolling log. */
private const val MAX_PROBE_ROWS = 12
