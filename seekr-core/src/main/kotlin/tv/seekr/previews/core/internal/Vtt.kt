package tv.seekr.previews.core.internal

import kotlin.math.roundToLong
import tv.seekr.previews.core.SeekrTile

/**
 * Parser for the Seekr WebVTT sprite manifest.
 *
 * The grammar is plain WebVTT: a `WEBVTT` header, then one cue per thumbnail. Each cue
 * is a timing line (`START --> END`) followed by exactly one payload line — the signed
 * tile URL with a trailing `#xywh=x,y,w,h` fragment. There are no cue identifiers and no
 * cue settings, so we read each timing line and take the next non-blank line as its tile.
 */
internal object Vtt {

    fun parse(vtt: String, scale: Double): List<VttCue> {
        val cues = ArrayList<VttCue>()
        val lines = vtt.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val parts = line.split("-->")
                if (parts.size == 2) {
                    val startMs = parseTime(parts[0].trim())
                    val endMs = parseTime(parts[1].trim())
                    val payload = lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim()
                    val tile = payload?.let { parseTile(it) }
                    if (tile != null) {
                        cues += VttCue(
                            startMs = (startMs * scale).roundToLong(),
                            endMs = (endMs * scale).roundToLong(),
                            tile = tile,
                        )
                    }
                }
            }
            i++
        }
        // Cues should already be ordered, but the position lookup binary-searches them,
        // so guarantee the invariant rather than trust the source.
        cues.sortBy { it.startMs }
        return cues
    }

    private fun parseTile(payload: String): SeekrTile? {
        val hashIdx = payload.lastIndexOf('#')
        if (hashIdx < 0) return null
        val sheetUrl = payload.substring(0, hashIdx)
        val fragment = payload.substring(hashIdx + 1)
        if (!fragment.startsWith("xywh=")) return null
        val coords = fragment.removePrefix("xywh=").split(",")
        if (coords.size < 4) return null
        val x = coords[0].trim().toIntOrNull() ?: return null
        val y = coords[1].trim().toIntOrNull() ?: return null
        val w = coords[2].trim().toIntOrNull() ?: return null
        val h = coords[3].trim().toIntOrNull() ?: return null
        return SeekrTile(sheetUrl = sheetUrl, x = x, y = y, w = w, h = h)
    }

    private fun parseTime(time: String): Long {
        val parts = time.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toDoubleOrNull() ?: 0.0
                h * 3_600_000L + m * 60_000L + (s * 1000).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toDoubleOrNull() ?: 0.0
                m * 60_000L + (s * 1000).toLong()
            }
            else -> 0L
        }
    }
}
