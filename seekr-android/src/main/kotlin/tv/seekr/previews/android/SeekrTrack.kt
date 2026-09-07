package tv.seekr.previews.android

import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tv.seekr.previews.android.internal.SheetCache
import tv.seekr.previews.core.PreviewTrack
import tv.seekr.previews.core.SeekrTile

/**
 * Android-flavored preview track: returns cropped [Bitmap]s by playback position.
 * Obtain one from [Seekr.loadTrack] and hold it for the playback session.
 */
class SeekrTrack internal constructor(
    private val track: PreviewTrack,
    private val sheets: SheetCache,
) {
    /** True when the title has no previews. */
    val isEmpty: Boolean get() = track.isEmpty

    /**
     * Duration, in milliseconds, of the media this track's sprite sheets were generated from.
     * See [tv.seekr.previews.core.PreviewTrack.sourceDurationMs] — in particular, why the
     * difference between this and the `durationMs` you passed to `loadTrack` must **not** be
     * auto-applied to [offsetMs].
     */
    val sourceDurationMs: Long get() = track.sourceDurationMs

    /**
     * Timebase scale factor reported by the backend. See
     * [tv.seekr.previews.core.PreviewTrack.scale] — diagnostics only, almost always `1.0`.
     */
    val scale: Double get() = track.scale

    /**
     * Signed milliseconds added to a requested position before the preview is looked up.
     * A **negative** value suits the case where the release being played has extra
     * **head** content (a logo or black frames) the sprite source lacked; a **positive**
     * value suits the reverse. See [tv.seekr.previews.core.PreviewTrack.offsetMs] for the
     * full sign convention and a worked example.
     *
     * Defaults to `0`, and that is the right default: the offset depends on *where* two
     * releases differ, not on how much their durations differ, so it cannot be derived from
     * [sourceDurationMs]. See [tv.seekr.previews.core.PreviewTrack.sourceDurationMs] for why
     * `sourceDurationMs - durationMs` must not be auto-applied, and prefer a user-facing
     * "preview sync +/-" control instead.
     *
     * Safe to set from the UI thread — e.g. from that sync control next to the scrubber —
     * while [thumbnailFor] is polled from a coroutine.
     */
    var offsetMs: Long
        get() = track.offsetMs
        set(value) {
            track.offsetMs = value
        }

    /**
     * Eagerly downloads and caches all sprite sheets for this track in parallel.
     * Call once after [tv.seekr.previews.android.Seekr.loadTrack] so every subsequent
     * [thumbnailAt] call hits the in-memory cache instead of the network.
     */
    suspend fun prefetchSheets() {
        coroutineScope {
            track.sheetUrls.map { url -> async { sheets.get(url) } }.awaitAll()
        }
    }

    /**
     * The thumbnail for [positionMs] as a cropped [Bitmap], or `null` if unavailable.
     *
     * The full sprite sheet is downloaded and decoded once (then cached), so the first
     * call may touch the network while later calls only crop in memory. Safe to call from
     * the main thread — the body switches to background dispatchers for I/O and pixels.
     *
     * Call this whenever your scrub position changes; throttle to your UI's frame rate if
     * the user is dragging quickly.
     */
    suspend fun thumbnailAt(positionMs: Long): Bitmap? {
        val tile = track.tileAt(positionMs) ?: return null
        return cropTile(tile)
    }

    /**
     * The thumbnail for [positionMs] as a cropped [Bitmap], bundled with the resolved cue's
     * time window it was drawn from — `null` if unavailable.
     *
     * [thumbnailAt] discards the cue's time window, so a player has no way to know that the
     * bitmap it got back actually represents [SeekrThumbnail.cueStartMs], not the exact
     * position it asked for — e.g. a request for `163_174` may resolve to a cue covering
     * `160_000..170_000`. If the player seeks to `163_174` (what the user actually dragged to)
     * while showing that thumbnail, the frame it lands on after the seek will not match the
     * thumbnail just shown.
     *
     * [SeekrThumbnail.cueStartMs] is the timestamp the thumbnail represents. Seeking there
     * instead of to the originally requested position makes the preview and the resulting
     * playback frame agree exactly. The trade-off is that the seek can land up to one cue
     * interval away from where the user actually dragged. This library does not make that
     * choice for you — decide per integration whether exact preview/playback agreement or
     * exact positional accuracy matters more, and seek to [SeekrThumbnail.cueStartMs] or the
     * original position accordingly.
     *
     * ### This is a floor lookup, not a nearest lookup
     * The cue returned *contains* [positionMs], and its frame was captured at its **start**,
     * so it holds the closest available frame only for the first half of its window. At
     * `18_500` against a `160_000`-style 10s grid the frame is 8.5s stale while one 1.5s away
     * exists at the cue's end. Past the midpoint, prefer the successor:
     * ```
     * val cue = track.thumbnailFor(positionMs) ?: return
     * val preferSuccessor = (positionMs - cue.cueStartMs) > (cue.cueEndMs - positionMs)
     * val shown = if (preferSuccessor) track.thumbnailFor(cue.cueEndMs) ?: cue else cue
     * ```
     * The comparison avoids dividing by two so it stays exact on a non-uniform cue grid.
     * Whichever cue you show, label it with its `cueStartMs` rather than the user's raw scrub
     * position — see the "Preview accuracy" section of the README.
     *
     * [SeekrThumbnail.cueStartMs] currently holds the cue *grid* time, not necessarily the
     * exact source frame timestamp: the sprite generator snaps frame extraction to the
     * nearest keyframe within +/-3s of the grid target and labels the cue with that grid
     * target, so the frame actually stored in the returned bitmap can be up to 3 seconds away
     * from [SeekrThumbnail.cueStartMs]. A planned generator change will instead write the real
     * keyframe time as the cue start, at which point [SeekrThumbnail.cueStartMs] becomes exact
     * with no change to this API — but until then, treat it as accurate to within a few
     * seconds, not to the millisecond.
     *
     * Same caching/dispatcher behaviour as [thumbnailAt] — see its docs.
     */
    suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val cue = track.cueAt(positionMs) ?: return null
        val bitmap = cropTile(cue.tile) ?: return null
        return SeekrThumbnail(bitmap = bitmap, cueStartMs = cue.startMs, cueEndMs = cue.endMs)
    }

    private suspend fun cropTile(tile: SeekrTile): Bitmap? {
        val sheet = sheets.get(tile.sheetUrl) ?: return null
        // ponytail: crop runs on the caller's dispatcher (Main) — it's a sub-ms pixel copy
        // for typical thumbnail sizes. Avoids a Dispatchers.Default context switch which
        // adds a suspension point and lets collectLatest cancel before the result lands.
        val tileW = tile.w.takeIf { it > 0 } ?: 320
        val tileH = tile.h.takeIf { it > 0 } ?: 180
        val x = tile.x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val w = tileW.coerceAtMost((sheet.width - x).coerceAtLeast(1))
        val h = tileH.coerceAtMost((sheet.height - y).coerceAtLeast(1))
        return runCatching { Bitmap.createBitmap(sheet, x, y, w, h) }.getOrNull()
    }
}

/**
 * A cropped preview [bitmap] together with the resolved cue window it was drawn from.
 *
 * See [SeekrTrack.thumbnailFor] for the full explanation of what [cueStartMs] means, why you
 * might seek there instead of the originally requested position, and its current precision.
 */
data class SeekrThumbnail(
    val bitmap: Bitmap,
    val cueStartMs: Long,
    val cueEndMs: Long,
)
