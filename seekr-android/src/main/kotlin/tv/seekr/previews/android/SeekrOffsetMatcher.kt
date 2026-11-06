package tv.seekr.previews.android

import tv.seekr.previews.android.internal.toCoreSample
import tv.seekr.previews.core.MatchStatus
import tv.seekr.previews.core.OffsetMatch
import tv.seekr.previews.core.OffsetMatcher

/**
 * An offset-matching session that accumulates evidence across bursts of Android bitmaps.
 *
 * Obtain one from [SeekrTrack.offsetMatcher]. Feed it a burst whenever your player manages
 * to capture one, and ask in the same call whether that was enough:
 *
 * ```
 * val matcher = track.offsetMatcher()
 * // ... as playback proceeds, roughly every 30-60s:
 * val match = matcher.offer(BitmapSample(frames))
 * if (match != null) {
 *     track.offsetMs = match.offsetMs        // apply silently — no dialog, no toast
 * } else if (matcher.isTerminal) {
 *     stopCapturingFrames()                  // nothing will ever come of it
 * }
 * ```
 *
 * Exists so a player that cannot seek — a P2P stream, where an arbitrary seek lands on
 * pieces nobody has downloaded — can collect frames as playback reaches them instead of
 * buffering five bursts itself. See [tv.seekr.previews.core.OffsetMatcher] for the details.
 */
class SeekrOffsetMatcher internal constructor(private val delegate: OffsetMatcher) {

    /** Typical spacing between cues. Space the frames in a burst `cueIntervalMs / 4` apart. */
    val cueIntervalMs: Long get() = delegate.cueIntervalMs

    /** Why there is no match yet, or [MatchStatus.MATCHED]. */
    val status: MatchStatus get() = delegate.status

    /**
     * The best match the evidence currently supports, or `null`. Withdrawn if later bursts
     * cost it consensus — read [status] before this one when polling. See
     * [tv.seekr.previews.core.OffsetMatcher.match].
     */
    val match: OffsetMatch? get() = delegate.match

    /** How many bursts contributed an estimate. Bursts with no usable frames do not count. */
    val burstsAccepted: Int get() = delegate.burstsAccepted

    /**
     * `true` when no future burst can change the outcome. Stop capturing frames — a readback
     * per frame costs battery, and repeating it for a title that structurally cannot match
     * is pure loss.
     */
    val isTerminal: Boolean get() = delegate.isTerminal

    /** Adds one burst and returns the current match, or `null` if there still isn't one. */
    suspend fun offer(sample: BitmapSample): OffsetMatch? = delegate.offer(sample.toCoreSample())

    /** Feeds several bursts in order, returning the match once any of them produces one. */
    suspend fun offerAll(samples: List<BitmapSample>): OffsetMatch? =
        delegate.offerAll(samples.map { it.toCoreSample() })

    /**
     * Discards accumulated bursts and any match so the session can be reused — after a user
     * override, say. Does not re-download hashes and does not clear a terminal status.
     */
    suspend fun reset() {
        delegate.reset()
    }
}
