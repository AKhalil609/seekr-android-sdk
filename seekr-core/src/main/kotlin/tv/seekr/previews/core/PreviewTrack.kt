package tv.seekr.previews.core

import java.util.concurrent.atomic.AtomicLong
import tv.seekr.previews.core.internal.VttCue

/**
 * The full set of preview thumbnails for one title, ready to query by playback position.
 * Obtain one from [SeekrPreviews.loadTrack]. Cheap to hold for the whole playback session.
 *
 * ### Preview sync offset
 * Sprite sheets are generated once from a specific release of a title, but a user may be
 * playing a different release whose duration differs slightly (e.g. an extra distributor
 * logo or black frames at the head). Because the backend never rescales the preview
 * timeline (see `internal/sprites/metadata.go` `scale()`), this shows up as a constant
 * offset between the preview timeline and the user's playback timeline, uniform across the
 * whole title — conceptually identical to a subtitle sync offset.
 *
 * [offsetMs] corrects for this: it is added to the position *before* the cue lookup.
 *
 * - **Negative** offset — the release being played has *extra* head content (the common case:
 *   a distributor logo or black frames the sprite source didn't have). A scene sitting at
 *   00:10:00 in the sprite source is at 00:10:30 in playback, so set `offsetMs = -30_000`.
 *   `tileAt(630_000)` — the user's actual playback position — then resolves to `600_000` in
 *   the preview timeline, returning the correct tile.
 * - **Positive** offset — the release being played is *missing* head content the sprite source
 *   had. A scene at 00:10:30 in the sprite source is at 00:10:00 in playback, so set
 *   `offsetMs = 30_000` and `tileAt(600_000)` resolves to `630_000`.
 *
 * In both directions the offset is a property of *where* the two releases differ, not of how
 * much their durations differ. See [sourceDurationMs] for why `sourceDurationMs - durationMs`
 * must **not** be auto-applied as the offset, and what to do instead.
 *
 * [offsetMs] is a plain `var` backed by an [AtomicLong] rather than an immutable
 * `withOffset(...)` copy: the intended usage is a single long-lived [PreviewTrack] held for
 * the whole playback session, nudged in real time from a "sync +/-" UI control (exactly like
 * a subtitle delay control) while [tileAt] keeps being polled from a coroutine on every scrub
 * event. Requiring callers to swap in a new immutable instance on every nudge — and thread
 * that new instance through [tv.seekr.previews.android.SeekrTrack] and any Compose state —
 * would add churn for no benefit, since the cue list itself never changes. The [AtomicLong]
 * makes "set from the UI thread, read from a coroutine" safe without external synchronization.
 */
class PreviewTrack internal constructor(
    private val cues: List<VttCue>,
    /**
     * Duration, in milliseconds, of the media that this track's sprite sheets were actually
     * generated from — as reported by the backend's `/sprites` lookup (`source_duration_ms`).
     *
     * Comparing this value against the `durationMs` you passed to [SeekrPreviews.loadTrack]
     * tells you whether the release you're playing is the same *length* as the release the
     * previews were generated from:
     * ```
     * val driftMs = track.sourceDurationMs - durationMsPassedToLoadTrack
     * ```
     *
     * ### Do not assign `driftMs` to [offsetMs]
     * It is tempting, and it is wrong more often than it is right. A duration delta says the
     * two releases differ in length; it says nothing about *where* they differ, and only a
     * difference at the **head** produces a constant shift equal to that delta:
     *
     * | Where the releases differ | `driftMs` | Correct [offsetMs] |
     * |---|---|---|
     * | Extra 30s logo at the head | -30_000 | -30_000 (the formula happens to be right) |
     * | Extra 30s of credits at the tail | -30_000 | **0** — previews were already correct |
     * | 30s trimmed from the middle | -30_000 | no constant value is correct |
     * | Container padding / rounding / VFR reporting | a few hundred ms | ~0 |
     *
     * Tail differences (regional credit rolls, retail vs. streaming masters) are at least as
     * common as head differences, so auto-seeding from `driftMs` routinely *breaks* titles
     * whose previews were already correct. Worse, the resulting error is uniform, so it looks
     * like a correct preview of the wrong moment rather than an obvious bug — users quietly
     * stop trusting the scrubber instead of reporting it.
     *
     * Leave [offsetMs] at `0` by default and expose a manual "preview sync +/-" control (the
     * same affordance as a subtitle delay), persisted per title. Use `driftMs` only as a hint
     * that drift is *plausible* — e.g. to surface that control more prominently, or as a
     * clearly-labelled, undoable one-tap suggestion inside it.
     *
     * Defaults to `0` when the backend response omits `source_duration_ms` (older backends),
     * in which case no drift can be computed at all — guard for that before using it.
     */
    val sourceDurationMs: Long = 0,
    /**
     * Timebase scale factor reported by the backend's `/sprites` lookup (`scale`). The SDK
     * already applies this scale server-side when fetching cues (see `st=1` in
     * `internal/LookupClient`), so cue timestamps in this track are already expressed on the
     * client's timeline — you should not need to apply this factor yourself.
     *
     * Exposed for diagnostics / logging only. In the overwhelming majority of cases this is
     * `1.0`; a non-`1.0` value means the backend generated sprites at a different timebase
     * than the source media (e.g. a frame-rate conversion) and had to rescale.
     */
    val scale: Double = 1.0,
) {
    private val offset = AtomicLong(0L)

    /**
     * Signed milliseconds added to a requested position before the cue lookup. See the
     * class-level KDoc for the sign convention and a worked example. Defaults to `0`
     * (no correction). Safe to set from the UI thread while [tileAt] is being read from a
     * coroutine.
     */
    var offsetMs: Long
        get() = offset.get()
        set(value) = offset.set(value)

    /** True when the title has no previews. */
    val isEmpty: Boolean get() = cues.isEmpty()

    /** Number of thumbnails in the track. */
    val size: Int get() = cues.size

    /** All distinct sprite-sheet URLs referenced by this track, in cue order. */
    val sheetUrls: Set<String> get() = cues.mapTo(LinkedHashSet()) { it.tile.sheetUrl }

    /**
     * The [SeekrTile] covering [positionMs], after applying [offsetMs] (see class KDoc for
     * the sign convention). If the corrected position falls in a gap, returns the nearest
     * preceding tile; if it falls before the first cue or past the last cue (including as a
     * result of the offset), returns the first or last tile respectively, clamped so the
     * offset alone never produces a `null` result. Returns `null` only when the track itself
     * is empty.
     */
    fun tileAt(positionMs: Long): SeekrTile? = resolveCue(positionMs)?.tile

    /**
     * The [SeekrCue] covering [positionMs] — the same lookup as [tileAt], but also returning
     * the resolved cue's time window (`startMs`..`endMs`) instead of discarding it.
     *
     * ### Why you'd want this over [tileAt]
     * [tileAt] returns only the [SeekrTile] for the requested position, but the tile is drawn
     * from a cue that spans a whole time window, not the exact millisecond requested — e.g. a
     * request for `163_174` may resolve to a cue covering `160_000..170_000`. If your player
     * seeks to `163_174` (the position the user actually dragged to) while showing the preview
     * for that cue, the frame it lands on after the seek will not match the thumbnail the user
     * was just looking at, because the thumbnail actually represents `startMs`, not the
     * requested position.
     *
     * [SeekrCue.startMs] is the timestamp the thumbnail represents. Seeking there instead of to
     * the originally requested position makes the preview and the resulting playback frame
     * agree exactly. The trade-off is that the seek can land up to one cue interval away from
     * where the user actually dragged. This library does not make that choice for you — decide
     * per integration whether exact preview/playback agreement or exact positional accuracy
     * matters more, and seek to [SeekrCue.startMs] or the original position accordingly.
     *
     * ### This is a floor lookup, not a nearest lookup
     * The cue returned is the one that *contains* [positionMs], and each cue's frame was
     * captured at its **start**. So the containing cue holds the closest available frame only
     * for the first half of its window: at `18_500` against a `10_000..20_000` cue, the frame
     * you get is 8.5s stale while a frame 1.5s away exists at `20_000`. Past the midpoint you
     * almost always want the successor:
     * ```
     * val cue = track.cueAt(positionMs) ?: return
     * val preferSuccessor = (positionMs - cue.startMs) > (cue.endMs - positionMs)
     * val shown = if (preferSuccessor) track.cueAt(cue.endMs) ?: cue else cue
     * ```
     * The comparison avoids dividing by two so it stays exact on a non-uniform cue grid — cue
     * lengths are not guaranteed equal, and will be less uniform once cue starts carry real
     * keyframe times (see below).
     *
     * ### Precision of [SeekrCue.startMs] today
     * [SeekrCue.startMs] currently holds the cue *grid* time, not necessarily the exact source
     * frame timestamp: the sprite generator snaps frame extraction to the nearest keyframe
     * within +/-3s of the grid target and labels the cue with that grid target, so the frame
     * actually stored in the tile can be up to 3 seconds away from [SeekrCue.startMs]. A
     * planned generator change will instead write the real keyframe time as the cue start, at
     * which point [SeekrCue.startMs] becomes exact with no change to this API — but until then,
     * treat it as accurate to within a few seconds, not to the millisecond.
     */
    fun cueAt(positionMs: Long): SeekrCue? = resolveCue(positionMs)?.let {
        SeekrCue(tile = it.tile, startMs = it.startMs, endMs = it.endMs)
    }

    /**
     * Binary-searches [cues] for the last cue whose start is at or before [positionMs]
     * (corrected by [offsetMs]), shared by [tileAt] and [cueAt]. See their docs for the
     * clamping behaviour at the ends of the track.
     */
    private fun resolveCue(positionMs: Long): VttCue? {
        if (cues.isEmpty()) return null
        val correctedPositionMs = positionMs + offsetMs
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= correctedPositionMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (idx >= 0) cues[idx] else cues.first()
    }
}
