package tv.seekr.previews.android

import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.seekr.previews.android.internal.SheetCache
import tv.seekr.previews.android.internal.toCoreSample
import tv.seekr.previews.core.OffsetMatch
import tv.seekr.previews.core.PreviewTrack
import tv.seekr.previews.core.SeekrCue
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
     * `sourceDurationMs - durationMs` must not be auto-applied, and [matchOffset] for how to
     * measure the real value instead. Keep a user-facing "preview sync +/-" control for the
     * cases where matching declines.
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
     * Downloads the sprite sheets from the start of the film, two at a time (the most recent
     * `maxCachedSheets` are kept). Prefer [warm] with the playback position.
     */
    suspend fun prefetchSheets() = warm(0L)

    /**
     * Typical spacing between cues, in milliseconds. Use it to space the frames within a
     * burst for [matchOffset] — four frames, `cueIntervalMs / 4` apart.
     */
    val cueIntervalMs: Long get() = track.cueIntervalMs

    /**
     * Measures [offsetMs] automatically, by matching frames decoded from the video actually
     * being played against perceptual hashes of the release these sprite sheets came from.
     *
     * This is what replaces guessing the offset from the duration difference. That guess is
     * wrong whenever a release is trimmed at both ends — the gap is head + tail while only
     * head is a valid constant offset — and it is wrong *silently*, which is worse than
     * being wrong loudly.
     *
     * Returns `null` when the evidence does not support an answer. **Keep manual control in
     * that case**: leave [offsetMs] alone and let the user's sync slider decide. Declining is
     * normal, expected on roughly 14% of attempts even with good frames, and safe. A *wrong*
     * automatic sync is not recoverable by the user, because it looks like a correct preview
     * of the wrong moment rather than a bug.
     *
     * Nothing is applied for you:
     * ```
     * track.matchOffset(samples)?.let { track.offsetMs = it.offsetMs }
     * ```
     * Apply it silently — no dialog, no toast. And if the user is mid-scrub when the answer
     * lands, decide deliberately whether to defer it until the overlay closes rather than
     * shifting thumbnails under their hand.
     *
     * ### What to capture
     * Each [BitmapSample] is a **burst**: frames grabbed close together, spread evenly across
     * a single cue interval ([cueIntervalMs]).
     *
     * - **Four frames per burst.** Measured, not a rule of thumb: against a real second
     *   release, 1 frame per burst resolved 24.4% of the time, 2 resolved 53.0% and 4
     *   resolved 86.0%, against an 85.0% ceiling. Sprite thumbnails exist only at cue
     *   positions, so a single frame grabbed at an arbitrary moment is usually mid-shot with
     *   nothing resembling it in the hash list. One frame per burst is not four times
     *   cheaper — it is mostly broken.
     * - **Space them `cueIntervalMs / 4` apart** — about 2.5s on the usual 10s grid. Spreading
     *   wider than one interval buys nothing, because every cue is at most one interval away.
     * - **Five bursts across playback.** Three must agree; five gives the consensus rule
     *   something to reject.
     * - **Skip roughly the first 60 seconds.** Studio logos, black frames and title cards are
     *   hash-degenerate — the worst material to fingerprint.
     *
     * ### Bitmap size
     * Ask your player for a **small** bitmap. The first step of the reduction letterboxes the
     * frame into the sprite tile's box (usually 320x180) and the hash ends at 9x8, so almost
     * everything a bigger grab carries is discarded before it is measured — a full-resolution
     * readback buys nothing and costs a much more expensive GPU round trip on exactly the
     * devices least able to afford one.
     *
     * Measured against full-resolution 1920x1080 reductions of the same frames, over 160
     * synthetic scenes spanning band-limited and fine-textured content: a frame handed over at
     * 256x144 differs by a mean of 0.09 bits and never by more than 1; at 128x72, 0.12 bits
     * and never more than 1; even 64x36 peaked at 2 bits, against the ~6 bits a real re-encode
     * costs. Something like 256x144 is a comfortable default with plenty of margin.
     *
     * ### What it costs, and what it must never block
     * The hash list is fetched on the first call and cached — never during
     * [tv.seekr.previews.android.Seekr.loadTrack], and never on the path that draws a
     * thumbnail. Matching runs on background dispatchers and is microseconds of CPU per
     * frame. A slow or failed match is indistinguishable from never calling this at all,
     * which is the point: draw thumbnails immediately with the current [offsetMs] and swap in
     * a matched value only if one arrives.
     *
     * ### Collecting bursts over time
     * This form needs every burst up front. A player that cannot seek can only capture frames
     * as playback reaches them — use [offsetMatcher] for that, which also reports *why* it is
     * still returning `null` and when to give up.
     */
    suspend fun matchOffset(samples: List<BitmapSample>): OffsetMatch? =
        track.matchOffset(samples.map { it.toCoreSample() })

    /**
     * Starts an incremental matching session for callers that collect bursts over time.
     *
     * Feed it with [SeekrOffsetMatcher.offer] as each burst is captured; it returns `null`
     * until enough bursts agree. [SeekrOffsetMatcher.status] explains any `null`, and
     * [SeekrOffsetMatcher.isTerminal] tells you when to stop capturing frames because no
     * future burst can help.
     *
     * See [matchOffset] for how many frames each burst needs and how to space them.
     */
    fun offsetMatcher(): SeekrOffsetMatcher = SeekrOffsetMatcher(track.offsetMatcher())


    /**
     * The cue [steps] away from the one containing [positionMs] (after [offsetMs]), for
     * scrubbing from thumbnail to thumbnail. See [tv.seekr.previews.core.PreviewTrack.stepCue].
     * The cue is on the preview timeline; [toPlaybackMs] gives the player position to seek to.
     */
    fun stepCue(positionMs: Long, steps: Int): SeekrCue? = track.stepCue(positionMs, steps)

    /** The player position that shows the frame of a preview-timeline moment (a cue start): the inverse of [offsetMs]. */
    fun toPlaybackMs(previewMs: Long): Long = track.toPlaybackMs(previewMs)

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

    /**
     * Downloads the sprite sheets around [positionMs] ahead of a scrub: the sheet under the
     * playhead first, then outwards, two at a time. Call it when playback starts (and again
     * after a long seek) so the first thumbnails the user asks for are already local. Returns
     * once every sheet is held or failed; cancel the calling coroutine to stop. Only the
     * compressed sheets are kept (see [Seekr.create]'s `maxCachedSheets`), so this is cheap
     * in memory even for a long film.
     */
    suspend fun warm(positionMs: Long) {
        val gate = Semaphore(WARM_PARALLELISM)
        coroutineScope {
            track.sheetUrlsNear(positionMs).map { url -> async { gate.withPermit { sheets.load(url) } } }.awaitAll()
        }
    }

    /**
     * A filmstrip around [positionMs]: `before + after + 1` slots in time order with the
     * thumbnail under the playhead at index [before] (see [cueStartMs][SeekrThumbnail.cueStartMs]
     * for what each one stands for). A slot is `null` past either end of the track and when its
     * sheet is not downloaded yet. **Never waits for the network**: a missing sheet is not
     * requested here, so a scrub does not queue downloads; start them with [warm].
     *
     * The bitmaps are shared with an internal cache: do not recycle or modify them.
     */
    suspend fun thumbnailStrip(positionMs: Long, before: Int, after: Int): List<SeekrThumbnail?> =
        track.cuesAround(positionMs, before, after).map { cue ->
            cue?.let { c ->
                val t = c.tile
                sheets.cachedTile(t.sheetUrl, t.x, t.y, t.w.takeIf { it > 0 } ?: TILE_W, t.h.takeIf { it > 0 } ?: TILE_H)
                    ?.let { SeekrThumbnail(bitmap = it, cueStartMs = c.startMs, cueEndMs = c.endMs) }
            }
        }

    private suspend fun cropTile(tile: SeekrTile): Bitmap? =
        sheets.tile(tile.sheetUrl, tile.x, tile.y, tile.w.takeIf { it > 0 } ?: TILE_W, tile.h.takeIf { it > 0 } ?: TILE_H)

    private companion object {
        const val TILE_W = 320
        const val TILE_H = 180
        const val WARM_PARALLELISM = 2
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
