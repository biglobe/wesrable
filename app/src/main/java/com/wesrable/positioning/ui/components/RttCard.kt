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
    azInitiatorSupported: Boolean,
    azCapabilityKey: String?,
    characteristics: Map<String, Boolean>,
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
                Text("802.11az", style = MaterialTheme.typography.titleSmall)
                Text(
                    "802.11az is the successor to 802.11mc — tighter, and built to serve " +
                        "many clients at once. It is not a separate request: the platform " +
                        "negotiates it inside an ordinary ranging call when both ends can, " +
                        "so the probe below has always been attempting it. What was missing " +
                        "was knowing whether it happened.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        azInitiatorSupported ->
                            "This phone can initiate 802.11az. Any access point that supports " +
                                "it will range over az rather than mc, and the probe marks which."
                        azCapabilityKey != null ->
                            "This phone reports no 802.11az initiator support, so ranging here " +
                                "can only use the older 802.11mc. That is a property of the " +
                                "phone's chipset and firmware, not of the building."
                        else ->
                            "No 802.11az capability key appears in this radio's bundle at all. " +
                                "That means the platform does not report az support either way " +
                                "— not that az is known to be absent. Check the full list below " +
                                "against the verdict."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (azCapabilityKey != null) {
                    Text(
                        "Read from: $azCapabilityKey",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (characteristics.isNotEmpty()) {
                    Text(
                        "Radio reports: " + shortenKeys(characteristics)
                            .entries.sortedBy { it.key }
                            .joinToString("  ") { (key, on) ->
                                "$key=${if (on) "yes" else "no"}"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        "The radio exposes no capability list on this Android version " +
                            "(it arrived in Android 13), so az support cannot be read " +
                            "ahead of time — only observed in a result.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

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
    val placeholder = probes.count { it.status == RttProbeStatus.NO_DISTANCE }
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
            placeholder > 0 -> "$placeholder access point${if (placeholder == 1) "" else "s"} " +
                "replied, but with a placeholder where the distance should be — the same " +
                "value regardless of how near or far they are, which is a status code " +
                "rather than a measurement. They are not true 802.11mc responders. " +
                "$refused refused outright and $failed did not answer."

            else -> "$refused refused outright and $failed failed to answer. No ranging " +
                "is available in this building, and now that is measured rather than " +
                "inferred from a beacon flag."
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )

    probes.take(MAX_PROBE_ROWS).forEach { probe ->
        Text(
            "%-16s %4d dBm %-3s %-13s %s".format(
                probe.ssid.take(16),
                probe.rssiDbm,
                bandOf(probe.frequencyMhz),
                when (probe.status) {
                    RttProbeStatus.RANGED -> if (probe.rangedVia80211az) "RANGED az" else "RANGED mc"
                    RttProbeStatus.NO_DISTANCE -> "no distance"
                    RttProbeStatus.NOT_SUPPORTED -> "refused"
                    RttProbeStatus.FAILED -> "no answer"
                },
                when (probe.status) {
                    RttProbeStatus.RANGED -> "%.2f m +/-%.2f (%d/%d)".format(
                        probe.distanceMeters ?: 0.0,
                        probe.standardDeviationMeters ?: 0.0,
                        probe.successfulMeasurements,
                        probe.attemptedMeasurements,
                    )
                    // Showing the rejected number matters: it is what makes a
                    // placeholder recognisable as one.
                    RttProbeStatus.NO_DISTANCE ->
                        "returned %.0f m".format(probe.distanceMeters ?: 0.0)
                    else -> when {
                        probe.advertisedAzResponder -> "(advertised 11az)"
                        probe.advertisedResponder -> "(advertised 11mc)"
                        else -> ""
                    }
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }

    val advertisedAz = probes.count { it.advertisedAzResponder }
    val rangedAz = probes.count { it.rangedVia80211az }
    if (advertisedAz > 0 || rangedAz > 0) {
        Text(
            "802.11az: $advertisedAz access point${if (advertisedAz == 1) "" else "s"} " +
                "advertise it, $rangedAz ranged over it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    val advertisedButSilent = probes.count {
        it.advertisedResponder && it.status != RttProbeStatus.RANGED
    }
    val silentButRanged = probes.count {
        !it.advertisedResponder && it.status == RttProbeStatus.RANGED
    }
    if (ranged == 0 && placeholder > 0) {
        Text(
            "So RTT is not usable here after all. Every reply came through the " +
                "non-802.11mc path, which reports success without measuring anything. " +
                "One genuine 802.11mc router — Google/Nest WiFi, or most mesh systems " +
                "since about 2019 — would change that.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
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

/**
 * Which radio an access point is on. FTM is often enabled on a router's 5 or
 * 6 GHz radio and not its 2.4 GHz one, so the same box can answer under one
 * SSID and stay silent under another — and without the band that reads as an
 * inconsistent router rather than a per-radio setting.
 */
private fun bandOf(frequencyMhz: Int): String = when {
    frequencyMhz == 0 -> ""
    frequencyMhz < 2500 -> "2G"
    frequencyMhz < 5900 -> "5G"
    else -> "6G"
}

/**
 * Drops the prefix every capability key shares, and nothing else.
 *
 * A first attempt kept only the text after the last underscore, which turned
 * `ntb_initiator` into "initiator" — indistinguishable from the one-sided-RTT
 * initiator — and rendered two separate flags as an identical "supported".
 * Names exist to be told apart, and abbreviating them until they collide is
 * worse than printing them in full.
 */
private fun shortenKeys(characteristics: Map<String, Boolean>): Map<String, Boolean> {
    if (characteristics.size < 2) return characteristics
    val keys = characteristics.keys.toList()
    var shared = 0
    val shortest = keys.minOf { it.length }
    while (shared < shortest && keys.all { it[shared] == keys[0][shared] }) shared++
    // Only trim on a separator, so a shared prefix does not cut mid-word.
    val cut = keys[0].take(shared).lastIndexOf('_') + 1
    return characteristics.mapKeys { (key, _) -> key.drop(cut) }
}

/** Enough to see the pattern without turning the card into a scrolling log. */
private const val MAX_PROBE_ROWS = 12
