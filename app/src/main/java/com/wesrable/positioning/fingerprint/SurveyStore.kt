package com.wesrable.positioning.fingerprint

import android.content.Context
import com.wesrable.positioning.model.SurveyPoint
import java.io.File

/**
 * Persists the dense survey to a tab-separated file in app-private storage,
 * alongside the room fingerprints and the stored map.
 *
 * Surviving between sessions is not a convenience here, it is the point.
 * The report deliberately refuses to compare samples taken within seconds of
 * each other, so a survey walked in one go yields far fewer usable pairs than
 * the same route walked again a day later. Keeping the survey lets the
 * measurement get better every time the user walks the house.
 */
class SurveyStore(context: Context) {

    private val file = File(context.filesDir, "survey.tsv")

    fun load(): List<SurveyPoint> {
        if (!file.exists()) return emptyList()
        return buildList {
            file.forEachLine { line -> parseLine(line)?.let { add(it) } }
        }
    }

    fun save(points: List<SurveyPoint>) {
        if (points.isEmpty()) {
            clear()
            return
        }
        file.bufferedWriter().use { writer ->
            points.forEach { point ->
                writer.write(encodeLine(point))
                writer.newLine()
            }
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun encodeLine(point: SurveyPoint): String = listOf(
        point.xMeters,
        point.yMeters,
        point.pathLengthMeters,
        point.recordedAtMillis,
        point.magneticMagnitudeUt?.toString() ?: "",
        point.wifiRssi.entries.joinToString(",") { "${it.key}=${it.value}" },
        point.bleRssi.entries.joinToString(",") { "${it.key}=${it.value}" },
    ).joinToString("\t")

    private fun parseLine(line: String): SurveyPoint? {
        if (line.isBlank()) return null
        val parts = line.split("\t")
        if (parts.size < 7) return null
        return try {
            SurveyPoint(
                xMeters = parts[0].toDouble(),
                yMeters = parts[1].toDouble(),
                pathLengthMeters = parts[2].toDouble(),
                recordedAtMillis = parts[3].toLong(),
                magneticMagnitudeUt = parts[4].toFloatOrNull(),
                wifiRssi = parseRssiMap(parts[5]),
                bleRssi = parseRssiMap(parts[6]),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun parseRssiMap(encoded: String): Map<String, Int> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split(",").mapNotNull { entry ->
            val splitAt = entry.lastIndexOf('=')
            if (splitAt <= 0) return@mapNotNull null
            val value = entry.substring(splitAt + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, splitAt) to value
        }.toMap()
    }
}
