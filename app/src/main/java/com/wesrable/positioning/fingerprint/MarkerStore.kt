package com.wesrable.positioning.fingerprint

import android.content.Context
import com.wesrable.positioning.model.MarkerLabel
import java.io.File

/**
 * Remembers which printed marker is stuck to which piece of furniture.
 *
 * This is the smallest and by far the most valuable store in the app. Every
 * other one holds a statistical belief that gets better or worse with more
 * walking; this one holds a fact the user stated, and marker 7 goes on meaning
 * the winter-clothes cabinet until they say otherwise.
 */
class MarkerStore(context: Context) {

    private val file = File(context.filesDir, "markers.tsv")
    private val labels = LinkedHashMap<Int, MarkerLabel>()

    init {
        load()
    }

    val all: List<MarkerLabel> get() = labels.values.sortedBy { it.markerId }

    fun labelFor(markerId: Int): MarkerLabel? = labels[markerId]

    /**
     * Names a marker, pinning it to where the user was standing.
     *
     * The position is taken as given rather than averaged over later sightings,
     * because it is the one moment the app knows exactly where the furniture
     * is: the user walked to it and said so. A later sighting from across the
     * room is a weaker claim about the same thing, and letting those drag the
     * pin around would trade a fact for an average.
     */
    fun name(markerId: Int, label: String, xMeters: Double, yMeters: Double) {
        val cleaned = label.replace('\t', ' ').replace('\n', ' ').trim()
        if (cleaned.isEmpty()) return
        val existing = labels[markerId]
        labels[markerId] = MarkerLabel(
            markerId = markerId,
            label = cleaned,
            xMeters = xMeters,
            yMeters = yMeters,
            seenCount = existing?.seenCount ?: 1,
            lastSeenMillis = System.currentTimeMillis(),
        )
        persist()
    }

    /** Records that a named marker was seen again; ignores unnamed ones. */
    fun noteSeen(markerIds: Collection<Int>) {
        var changed = false
        val now = System.currentTimeMillis()
        markerIds.forEach { id ->
            val existing = labels[id] ?: return@forEach
            // Once a second at most: the camera reports the same marker many
            // times a second, and rewriting the file at frame rate would be
            // absurd for a counter nobody reads that closely.
            if (now - existing.lastSeenMillis < SEEN_DEBOUNCE_MILLIS) return@forEach
            labels[id] = existing.copy(seenCount = existing.seenCount + 1, lastSeenMillis = now)
            changed = true
        }
        if (changed) persist()
    }

    fun forget(markerId: Int) {
        if (labels.remove(markerId) == null) return
        if (labels.isEmpty()) clear() else persist()
    }

    fun clear() {
        labels.clear()
        if (file.exists()) file.delete()
    }

    private fun load() {
        labels.clear()
        if (!file.exists()) return
        file.forEachLine { line ->
            val parts = line.split("\t")
            if (parts.size < 6) return@forEachLine
            val id = parts[0].toIntOrNull() ?: return@forEachLine
            val x = parts[2].toDoubleOrNull() ?: return@forEachLine
            val y = parts[3].toDoubleOrNull() ?: return@forEachLine
            labels[id] = MarkerLabel(
                markerId = id,
                label = parts[1],
                xMeters = x,
                yMeters = y,
                seenCount = parts[4].toIntOrNull() ?: 1,
                lastSeenMillis = parts[5].toLongOrNull() ?: 0L,
            )
        }
    }

    private fun persist() {
        file.bufferedWriter().use { writer ->
            labels.values.forEach {
                writer.write(
                    listOf(
                        it.markerId, it.label, it.xMeters, it.yMeters,
                        it.seenCount, it.lastSeenMillis,
                    ).joinToString("\t")
                )
                writer.newLine()
            }
        }
    }

    private companion object {
        const val SEEN_DEBOUNCE_MILLIS = 1_000L
    }
}
