package com.wesrable.positioning.fingerprint

import android.content.Context
import com.wesrable.positioning.model.Fingerprint
import java.io.File

/**
 * Persists recorded fingerprints to a plain tab-separated file in app-private
 * internal storage (no permission needed, not shared with other apps). Kept
 * dependency-free (no database/serialization library) since the schema is
 * tiny and fixed.
 */
class FingerprintStore(context: Context) {

    private val file = File(context.filesDir, "fingerprints.tsv")
    private val samples = mutableListOf<Fingerprint>()

    init {
        load()
    }

    val all: List<Fingerprint> get() = samples.toList()

    /** Sample count per recorded room label, for display. */
    fun labelCounts(): List<Pair<String, Int>> =
        samples.groupingBy { it.label }.eachCount().toList().sortedBy { it.first }

    fun add(fingerprint: Fingerprint) {
        samples.add(fingerprint)
        persist()
    }

    fun clear() {
        samples.clear()
        if (file.exists()) file.delete()
    }

    /** Drops every sample recorded under [label]. */
    fun remove(label: String) {
        if (!samples.removeAll { it.label == label }) return
        if (samples.isEmpty()) clear() else persist()
    }

    /**
     * Relabels every sample of [oldLabel]. Renaming onto a name already in use
     * merges the two rooms, which is the natural way to combine a room that
     * got recorded under two spellings.
     */
    fun rename(oldLabel: String, newLabel: String) {
        val cleaned = newLabel.replace('\t', ' ').replace('\n', ' ').trim()
        if (cleaned.isEmpty() || cleaned == oldLabel) return
        var changed = false
        for (index in samples.indices) {
            if (samples[index].label != oldLabel) continue
            samples[index] = samples[index].copy(label = cleaned)
            changed = true
        }
        if (changed) persist()
    }

    private fun load() {
        samples.clear()
        if (!file.exists()) return
        file.forEachLine { line ->
            parseLine(line)?.let { samples.add(it) }
        }
    }

    private fun persist() {
        file.bufferedWriter().use { writer ->
            samples.forEach { fingerprint ->
                writer.write(encodeLine(fingerprint))
                writer.newLine()
            }
        }
    }

    private fun encodeLine(fingerprint: Fingerprint): String {
        val safeLabel = fingerprint.label.replace('\t', ' ').replace('\n', ' ').trim()
        val wifi = fingerprint.wifiRssi.entries.joinToString(",") { "${it.key}=${it.value}" }
        val ble = fingerprint.bleRssi.entries.joinToString(",") { "${it.key}=${it.value}" }
        return listOf(
            safeLabel,
            fingerprint.recordedAtMillis,
            fingerprint.magneticMagnitudeUt,
            wifi,
            ble,
        ).joinToString("\t")
    }

    private fun parseLine(line: String): Fingerprint? {
        if (line.isBlank()) return null
        val parts = line.split("\t")
        if (parts.size < 5) return null
        return try {
            Fingerprint(
                label = parts[0],
                recordedAtMillis = parts[1].toLong(),
                magneticMagnitudeUt = parts[2].toFloat(),
                wifiRssi = parseRssiMap(parts[3]),
                bleRssi = parseRssiMap(parts[4]),
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
