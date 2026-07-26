package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.fingerprint.FingerprintCrossValidation
import com.wesrable.positioning.model.ResolutionBin
import com.wesrable.positioning.model.SurveyReport
import com.wesrable.positioning.model.SurveyReportStatus
import kotlin.math.roundToInt

/**
 * The dense survey and its report.
 *
 * Every other card here shows what the app currently believes. This one shows
 * how much that belief is worth, measured in this building rather than quoted
 * from someone else's: walk the house recording a signal sample every half
 * metre, walk it again another day, and the report says how far apart two
 * points have to be before the phone can reliably tell them apart.
 */
@Composable
fun SurveyCard(
    surveying: Boolean,
    pointCount: Int,
    report: SurveyReport,
    reportRunning: Boolean,
    onSetSurveying: (Boolean) -> Unit,
    onRunReport: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Dense survey & resolution report", style = MaterialTheme.typography.titleMedium)
            Text(
                "Records a signal sample every half metre as you walk, then scores " +
                    "them against each other: hold each sample out, locate it from the " +
                    "rest, and see how far off the answer was. This costs nothing but a " +
                    "walk, and it replaces guesswork about what this house supports " +
                    "with a measurement of it.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "$pointCount sample${if (pointCount == 1) "" else "s"} collected" +
                    if (surveying) " — recording" else "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { onSetSurveying(!surveying) }) {
                    Text(if (surveying) "Stop survey" else "Start survey")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRunReport, enabled = !reportRunning && pointCount > 0) {
                    Text("Run report")
                }
            }

            if (surveying) {
                Text(
                    "Walk the whole place slowly, then walk the same route again. " +
                        "The second pass is what makes the report possible: samples " +
                        "taken within " +
                        "${FingerprintCrossValidation.MIN_SEPARATION_MILLIS / 1000} s of " +
                        "each other are never compared, because two readings from the " +
                        "same moment agree for reasons that have nothing to do with " +
                        "location.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (reportRunning) {
                Text(
                    "Comparing every pair of samples…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                ReportBody(report)
            }

            if (pointCount > 0) {
                OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Clear survey")
                }
            }
        }
    }
}

@Composable
private fun ReportBody(report: SurveyReport) {
    when (report.status) {
        SurveyReportStatus.NOT_ENOUGH_POINTS -> {
            if (report.pointCount > 0) {
                Text(
                    "${report.pointCount} of ${FingerprintCrossValidation.MIN_POINTS} " +
                        "samples needed before any figure would mean anything. Keep walking.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SurveyReportStatus.NOT_ENOUGH_REVISITS -> {
            Text(
                "Enough samples, but only ${report.revisitPairCount} pair" +
                    "${if (report.revisitPairCount == 1) "" else "s"} of them were taken at " +
                    "the same spot on separate passes — " +
                    "${FingerprintCrossValidation.MIN_REVISIT_PAIRS} are needed. Without those " +
                    "there is no measurement of what \"the same place twice\" looks like, and " +
                    "so nothing to compare a different place against. Walk the same route " +
                    "again, ideally on another day.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            SignalCensus(report)
        }

        SurveyReportStatus.READY -> ReadyReport(report)
    }
}

/**
 * What the survey had to work with.
 *
 * A weak resolution curve has two explanations pointing opposite ways: the
 * building has few distinct signals, or the app is not using the ones it has.
 * The first means stop walking and buy hardware; the second is a bug. Nothing
 * else in the report distinguishes them, and guessing wrong wastes either an
 * afternoon or a purchase.
 */
@Composable
private fun SignalCensus(report: SurveyReport) {
    val census = report.census

    Text(
        "What the survey had to work with",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "WiFi ......  ${census.distinctWifiAps} access points seen, " +
            "${census.medianWifiPerSample} visible at a time\n" +
            "BLE .......  ${census.distinctBleDevices} devices seen, " +
            "${census.medianBlePerSample} visible at a time\n" +
            "Magnetic ..  ${census.samplesWithMagnetic} of ${report.pointCount} samples",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )

    val comparisons = report.comparedPairCount
    if (comparisons > 0) {
        Text(
            "Of $comparisons compared pairs: " +
                "${percent(census.pairsUsingWifi, comparisons)} used WiFi, " +
                "${percent(census.pairsUsingBle, comparisons)} used BLE, " +
                "${percent(census.pairsUsingMagnetic, comparisons)} used the magnetometer. " +
                "${percent(census.pairsWifiStale, comparisons)} had WiFi discarded as a " +
                "repeat of the same throttled scan.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Text(
        censusDiagnosis(report),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun percent(part: Int, whole: Int): String =
    if (whole == 0) "0%" else "${(part * 100.0 / whole).roundToInt()}%"

/**
 * Names the bottleneck. Ordered by what would be acted on first: a survey
 * running on one scalar magnetic value cannot work however far it is walked,
 * and saying so beats letting the user walk another five laps to find out.
 */
private fun censusDiagnosis(report: SurveyReport): String {
    val census = report.census
    val comparisons = report.comparedPairCount.coerceAtLeast(1)
    val wifiShare = census.pairsUsingWifi.toDouble() / comparisons
    val bleShare = census.pairsUsingBle.toDouble() / comparisons

    return when {
        wifiShare < 0.2 && bleShare < 0.2 ->
            "Almost every comparison here rests on the magnetometer alone — one number " +
                "per place. One number cannot distinguish many places, however far you " +
                "walk, so this is the ceiling and more passes will not lift it. WiFi is " +
                "throttled to one scan per 30 s by Android, and there appear to be few " +
                "BLE devices in range."

        census.distinctBleDevices == 0 ->
            "No BLE devices at all. That matters more than it sounds: WiFi is frozen by " +
                "Android's 30 s scan throttle, so BLE is what normally gives a dense " +
                "survey its half-metre detail. Without it the survey is carried by WiFi " +
                "readings that barely change and one magnetic value. A few cheap beacons " +
                "would change this measurement more than any amount of walking."

        census.distinctWifiAps < 5 ->
            "Only ${census.distinctWifiAps} access points across the whole survey. " +
                "Fingerprinting works by triangulating a pattern, and a pattern of four " +
                "numbers cannot say much — this is the sparse-signal case, and it is a " +
                "property of the building rather than of the app."

        census.pairsWifiStale > comparisons / 2 ->
            "More than half the pairs had their WiFi discarded as a repeat of the same " +
                "throttled scan, so this survey is mostly BLE and magnetics in practice. " +
                "Walking more slowly, or in longer laps, gives Android time to produce " +
                "fresh scans between samples."

        else ->
            "Signal supply looks reasonable: ${census.distinctWifiAps} access points and " +
                "${census.distinctBleDevices} BLE devices, both contributing to most " +
                "comparisons. If the resolution curve is still weak, that is the building " +
                "being genuinely ambiguous rather than anything missing here — more " +
                "passes will sharpen it, up to a point."
    }
}

@Composable
private fun ReadyReport(report: SurveyReport) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    Text("Where it puts you", style = MaterialTheme.typography.titleSmall)
    Text(
        "Holding out each sample and locating it from the others: " +
            "%.1f m off half the time, %.1f m or better nine times out of ten."
                .format(report.medianErrorMeters ?: 0.0, report.p90ErrorMeters ?: 0.0),
        style = MaterialTheme.typography.bodyMedium,
    )

    Text(
        "Telling two places apart",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "Of the sample pairs that were this far apart, how often the signals " +
            "differed by more than same-place noise does:",
        style = MaterialTheme.typography.bodySmall,
    )
    report.bins.filter { it.pairCount > 0 }.forEach { bin ->
        Text(
            binLine(bin),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }

    Text(
        verdict(report),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )

    SignalCensus(report)

    Text(
        "Based on ${report.comparedPairCount} compared pairs from ${report.pointCount} " +
            "samples over %.0f m, with the same-place noise floor at %.1f dB set by "
                .format(report.spanMeters, report.noiseFloorDb) +
            "${report.revisitPairCount} revisit pairs. Physical separations come from " +
            "dead reckoning, which drifts over a walk but is accurate between two points " +
            "a few metres apart — which is all these bands ask of it.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun binLine(bin: ResolutionBin): String {
    val range = if (bin.highMeters.isInfinite()) {
        "%4.1f m +   ".format(bin.lowMeters)
    } else {
        "%4.1f-%4.1f m".format(bin.lowMeters, bin.highMeters)
    }
    val percent = (bin.distinguishedFraction * 100).roundToInt()
    val bars = (bin.distinguishedFraction * 20).roundToInt().coerceIn(0, 20)
    return "$range  ${"%3d".format(percent)}%  ${"#".repeat(bars)}${"·".repeat(20 - bars)}" +
        "  (${bin.pairCount})"
}

/**
 * The one line that answers the question the survey was walked to answer.
 * Cabinets stand 30-60 cm apart, so anything short of resolving the 0-0.5 m
 * band means these signals cannot pick one cabinet out from its neighbour, and
 * saying so plainly is more useful than a table the user has to interpret.
 */
private fun verdict(report: SurveyReport): String {
    val resolved = report.resolvedAtMeters ?: return shortfallVerdict(report)

    return when {
        resolved < 0.5 -> "This house resolves below half a metre — the band that " +
            "matters for telling one cabinet from the next. That is an unusually rich " +
            "signal environment; worth re-running after another day's walk to confirm " +
            "it holds."

        resolved < 2.0 -> "This house resolves to about %.1f m. That is furniture-scale — "
            .format(resolved) +
            "enough to know which side of a room you are on, not enough to pick one " +
            "cabinet out from its neighbour 40 cm away. Closing that last gap needs " +
            "ranging hardware rather than more walking."

        else -> "This house resolves to about %.0f m — room scale.".format(resolved) +
            " Passive signals here place you in the right room and no finer. More " +
            "survey passes will sharpen this a little; going below a metre would need " +
            "a different measurement, not more of this one."
    }
}

/**
 * What to say when no band clears the 90% bar. The distinction worth drawing
 * is between "there is structure here, just not reliable enough" and "these
 * signals carry no location information at all" — the widest band with enough
 * pairs separates the two, and collapsing them into one gloomy sentence would
 * tell a user with a fixable survey to give up.
 */
private fun shortfallVerdict(report: SurveyReport): String {
    val widest = report.bins.lastOrNull { it.pairCount >= FingerprintCrossValidation.MIN_BIN_PAIRS }
        ?: return "Not enough pairs in any separation band yet. Walk further, and " +
            "cover the whole place rather than one room."

    val percent = (widest.distinguishedFraction * 100).roundToInt()
    val edge = "%.0f m".format(widest.lowMeters)

    return if (widest.distinguishedFraction < 0.3) {
        "No band reaches the 90% bar, and even places $edge apart are told apart only " +
            "$percent% of the time — barely above the $NOISE_BASELINE% that pure noise " +
            "scores. There is very little location information in the signals here. " +
            "Usually that means few access points and no BLE beacons in range."
    } else {
        "No band quite reaches the 90% bar: even $edge apart is $percent%. There is " +
            "real structure here — the curve climbs steadily with distance — but not " +
            "enough to rely on yet. Walking the survey again on another day is the " +
            "cheapest thing that helps, since it adds pairs at every separation."
    }
}

/**
 * The false-alarm rate the threshold is set at, so the bands can be read
 * against something. See [FingerprintCrossValidation.NOISE_FLOOR_PERCENTILE].
 */
private val NOISE_BASELINE =
    ((1.0 - FingerprintCrossValidation.NOISE_FLOOR_PERCENTILE) * 100).roundToInt()
