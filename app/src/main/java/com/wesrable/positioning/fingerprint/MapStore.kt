package com.wesrable.positioning.fingerprint

import android.content.Context
import com.wesrable.positioning.model.MapWaypoint
import com.wesrable.positioning.model.RoomAnchor
import com.wesrable.positioning.model.StoredMap
import java.io.File

/**
 * Persists the map itself — waypoints, walked geometry and room markers —
 * between sessions, so the app accumulates a model of a place instead of
 * redrawing it from nothing every time.
 *
 * Coordinates are meaningful across sessions only because east and north come
 * from the compass rather than from an arbitrary starting orientation; all a
 * later session has to recover is where its own origin sits, which is
 * `Relocalizer`'s job.
 *
 * Same plain tab-separated file as the fingerprints, for the same reason: the
 * schema is small and fixed, and a database dependency would buy nothing.
 * Lines are tagged by kind so the format can gain record types without
 * invalidating what is already on disk.
 */
class MapStore(context: Context) {

    private companion object {
        const val WAYPOINT = "W"
        const val TRAIL = "T"
        const val ROOM = "R"

        /** Bounds the file, and the work relocalization does per fix. */
        const val MAX_WAYPOINTS = 4000
        const val MAX_TRAIL_POINTS = 12000
    }

    private val file = File(context.filesDir, "map.tsv")

    fun load(): StoredMap {
        if (!file.exists()) return StoredMap()
        val waypoints = mutableListOf<MapWaypoint>()
        val trail = mutableListOf<Pair<Double, Double>>()
        val rooms = mutableListOf<RoomAnchor>()

        file.forEachLine { line ->
            val parts = line.split("\t")
            runCatching {
                when (parts.firstOrNull()) {
                    WAYPOINT -> if (parts.size >= 6) {
                        waypoints += MapWaypoint(
                            xMeters = parts[1].toDouble(),
                            yMeters = parts[2].toDouble(),
                            magneticMagnitudeUt = parts[3].toFloatOrNull(),
                            wifiRssi = decodeRssi(parts[4]),
                            bleRssi = decodeRssi(parts[5]),
                        )
                    }

                    TRAIL -> if (parts.size >= 3) {
                        trail += parts[1].toDouble() to parts[2].toDouble()
                    }

                    ROOM -> if (parts.size >= 5) {
                        rooms += RoomAnchor(
                            label = parts[1],
                            xMeters = parts[2].toDouble(),
                            yMeters = parts[3].toDouble(),
                            sightings = parts[4].toInt(),
                            // Everything reloaded is a remembered position, not
                            // one being observed right now.
                            isExact = true,
                        )
                    }
                }
            }
        }
        return StoredMap(waypoints, trail, rooms)
    }

    fun save(map: StoredMap) {
        file.bufferedWriter().use { writer ->
            map.waypoints.takeLast(MAX_WAYPOINTS).forEach { waypoint ->
                writer.write(
                    listOf(
                        WAYPOINT,
                        waypoint.xMeters,
                        waypoint.yMeters,
                        waypoint.magneticMagnitudeUt ?: "",
                        encodeRssi(waypoint.wifiRssi),
                        encodeRssi(waypoint.bleRssi),
                    ).joinToString("\t")
                )
                writer.newLine()
            }
            map.trail.takeLast(MAX_TRAIL_POINTS).forEach { (x, y) ->
                writer.write("$TRAIL\t$x\t$y")
                writer.newLine()
            }
            map.roomAnchors.forEach { room ->
                val label = room.label.replace('\t', ' ').replace('\n', ' ').trim()
                writer.write("$ROOM\t$label\t${room.xMeters}\t${room.yMeters}\t${room.sightings}")
                writer.newLine()
            }
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun encodeRssi(values: Map<String, Int>): String =
        values.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun decodeRssi(encoded: String): Map<String, Int> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split(",").mapNotNull { entry ->
            val splitAt = entry.lastIndexOf('=')
            if (splitAt <= 0) return@mapNotNull null
            val value = entry.substring(splitAt + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, splitAt) to value
        }.toMap()
    }
}
