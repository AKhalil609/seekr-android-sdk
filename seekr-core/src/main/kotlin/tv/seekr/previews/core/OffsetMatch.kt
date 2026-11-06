package tv.seekr.previews.core

/**
 * One decoded video frame, with the playback position it was taken from.
 *
 * [playbackMs] must be the position on the **player's** timeline — the same timeline you
 * pass to [PreviewTrack.tileAt] — because the offset the matcher returns is the difference
 * between that timeline and the preview timeline. A frame timestamped on any other clock
 * produces a confidently wrong answer.
 */
class PreviewFrame(
    val image: PixelSource,
    val playbackMs: Long,
)

/**
 * A **burst**: several frames decoded close together, spread evenly across one cue interval.
 *
 * A burst is the unit of evidence, not a single frame. Sprite thumbnails exist only at cue
 * positions, so a frame decoded at an arbitrary moment is usually mid-shot with nothing in
 * the hash list resembling it. Spreading a handful of frames across one interval guarantees
 * one of them lands near a cue, and the matcher keeps that one and throws the rest away.
 *
 * Use 4 frames per burst — see [PreviewTrack.matchOffset] for the measurements behind that
 * number, and for how to space them using [PreviewTrack.cueIntervalMs].
 */
class Sample(val frames: List<PreviewFrame>) {
    constructor(vararg frames: PreviewFrame) : this(frames.toList())
}

/**
 * A confident estimate of the offset between the release being played and the release the
 * sprite sheets were generated from.
 *
 * Apply it by assigning [offsetMs] to [PreviewTrack.offsetMs] (the SDK never applies it for
 * you — see [PreviewTrack.matchOffset]).
 */
data class OffsetMatch(
    /**
     * Signed milliseconds, in the same convention as [PreviewTrack.offsetMs]:
     * `previewTime = playbackTime + offsetMs`. A release **missing** head content the sprite
     * source had needs a **positive** offset.
     *
     * The value is quantised to the cue grid, because it is derived from the start time of
     * the cue that matched: expect roughly the true offset rounded to the nearest cue, not
     * the true offset to the millisecond.
     */
    val offsetMs: Long,
    /**
     * A `0f..1f` summary of how good the winning evidence was, blending how many bursts
     * agreed, how close the winning hashes were, how far ahead of the runner-up they were,
     * and how tightly the agreeing estimates clustered.
     *
     * **This is not a threshold to gate on, and there is deliberately no `isHighConfidence`
     * helper.** Receiving a non-`null` [OffsetMatch] at all *is* the gate: it means at least
     * three bursts agreed within tolerance, which is the rule that measured 0.0% mis-syncs.
     * Adding a second threshold on top would only ever reject matches the measured rule
     * accepted, on the strength of a heuristic that was never measured. Use this for
     * logging, and for choosing between two candidate matches.
     */
    val confidence: Float,
    /** How many bursts fell inside the winning cluster (at least 3). */
    val agreeingBursts: Int,
    /** How many bursts were weighed to produce this match (at most 5 — the rolling window). */
    val burstsConsidered: Int,
    /**
     * Median Hamming distance, in bits, between the winning frames and the sprite hashes
     * they matched. 0 is a byte-identical reduction; a real re-encode was measured at
     * around 6 bits; 20+ suggests the two frames are not the same picture.
     */
    val medianDistanceBits: Int,
    /**
     * Median distance to the *second*-best hash at the same frames. The gap between this and
     * [medianDistanceBits] is how unambiguous the matches were — a title with repeated
     * shots narrows it.
     */
    val medianRunnerUpBits: Int,
    /** Spread, in milliseconds, between the lowest and highest estimate in the cluster. */
    val spreadMs: Long,
    /**
     * Estimated timebase drift between the two releases, in parts per million, or `null`
     * when the agreeing bursts were too close together in playback time to fit a meaningful
     * line through.
     *
     * Purely diagnostic — **never applied**, because [PreviewTrack] models the correction as
     * a constant offset and cannot represent a slope. A large magnitude here (PAL speed-up
     * is ~40,000 ppm) means a constant offset will be right in the middle of the title and
     * drift at the ends, which the backend's `scale()` heuristic was supposed to catch.
     * Log it; if it is routinely large, that is a server-side bug report, not something the
     * client should silently compensate for.
     */
    val driftPpm: Double?,
)

/**
 * Why [OffsetMatcher] has not produced a match. Read it from [OffsetMatcher.status].
 *
 * Exists because "returned `null`" collapses six very different situations into one, and
 * they call for opposite reactions: [NEED_MORE_BURSTS] means keep feeding, while
 * [NO_HASHES_PUBLISHED] means stop capturing frames entirely and never try again for this
 * track. [OffsetMatcher.isTerminal] answers that question directly.
 */
enum class MatchStatus {
    /** Nothing fed yet, or not enough bursts have agreed *yet*. Keep feeding. */
    NEED_MORE_BURSTS,

    /**
     * Enough bursts arrived, but no three of them agreed within tolerance. Keep feeding —
     * the window rolls, so later bursts can still reach consensus. This is the matcher
     * declining, which is safe: the caller keeps manual control.
     */
    NO_CONSENSUS,

    /** A match is available from [OffsetMatcher.match]. */
    MATCHED,

    /** The track has no cues, so there is nothing to match against. Terminal. */
    EMPTY_TRACK,

    /**
     * This version publishes no hashes — either it predates fingerprinting or its sidecar
     * has not been uploaded. Terminal: nothing about this track will change.
     */
    NO_HASHES_PUBLISHED,

    /**
     * The backend published hashes under a reduction this SDK does not implement. Terminal.
     * Comparing them against ours would produce confident nonsense, so we decline instead.
     * Update the SDK.
     */
    UNSUPPORTED_ALGO,

    /**
     * The hash list and the cue list disagree on length, so index N is not reliably cue N.
     * Terminal, and deliberately fatal rather than best-effort: a shifted index yields a
     * wrong offset that looks exactly like a right one.
     */
    HASH_COUNT_MISMATCH,

    /**
     * The hashes could not be downloaded. Retried a bounded number of times and then
     * terminal — a blip should not disable the feature, and a dead URL should not be
     * re-fetched on every burst for the rest of the session.
     */
    HASH_FETCH_FAILED,
}
