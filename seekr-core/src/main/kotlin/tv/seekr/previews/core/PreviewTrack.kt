package tv.seekr.previews.core

import tv.seekr.previews.core.internal.VttCue

/**
 * The full set of preview thumbnails for one title, ready to query by playback position.
 * Obtain one from [SeekrPreviews.loadTrack]. Immutable and cheap to hold for the whole
 * playback session.
 */
class PreviewTrack internal constructor(
    private val cues: List<VttCue>,
) {
    /** True when the title has no previews. */
    val isEmpty: Boolean get() = cues.isEmpty()

    /** Number of thumbnails in the track. */
    val size: Int get() = cues.size

    /**
     * The [SeekrTile] covering [positionMs]. If the position falls in a gap, returns the
     * nearest preceding tile; before the first cue, returns the first tile; null only when
     * the track is empty.
     */
    fun tileAt(positionMs: Long): SeekrTile? {
        if (cues.isEmpty()) return null
        // Binary-search the last cue whose start is at or before the position.
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return (if (idx >= 0) cues[idx] else cues.first()).tile
    }
}
