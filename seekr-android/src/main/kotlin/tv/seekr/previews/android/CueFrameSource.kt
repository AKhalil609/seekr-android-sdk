package tv.seekr.previews.android

/** What the contributor does with the player: [WhileWatching] captures the cues the viewer plays through; [FullTitle] also fills in the rest. */
enum class ContributionMode {
    /** One PixelCopy frame per 10 s cue as it is played (the default; free, no extra decoding). */
    WhileWatching,

    /**
     * While the player is open (playing or paused), additionally contribute every cue the server still needs, in the background, from frames the
     * app decodes separately through a [CueFrameSource]. Needs a source; without one the session runs as [WhileWatching].
     */
    FullTitle,
}

/**
 * Decodes the frame nearest a cue's time, independently of the player's own pipeline (the app implements it, e.g. with a `MediaExtractor` +
 * `MediaCodec` or `MediaMetadataRetriever` on the same file; the SDK has no media3 dependency).
 *
 * The SDK calls it from one dedicated low-priority thread, at most one call at a time, and paces the calls (see `docs/full-capture.md`).
 * Implementations must be cancellable (a stopped session cancels the call; use `suspendCancellableCoroutine`/`runInterruptible` around blocking
 * decode work) and must not touch the main thread. A call that takes longer than 8 s is cancelled and counts as an error.
 */
interface CueFrameSource {
    /**
     * The frame at or nearest [targetMs] (= `cueIndex * 10 000`). Any size/aspect is fine: [TileEncoder] letterboxes to 320x180, so prefer
     * decoding small. Return [CueFrameResult.Skip] for a cue that cannot be used, [CueFrameResult.Fatal] when the source cannot work at all.
     */
    suspend fun frameAt(cueIndex: Int, targetMs: Long): CueFrameResult

    /**
     * Releases the decoder. Called exactly once by the SDK when the session ends or full capture falls back, never while a [frameAt] is running.
     * Once a source is passed to `startSession` the SDK owns it (it is closed even when the session refuses to start).
     */
    fun close()
}

sealed interface CueFrameResult {
    /** [ptsMs] is the decoded frame's presentation time; it is verified like a played frame (within 3 000 ms of the cue, else the tile is dropped). */
    data class Frame(val frame: CapturedVideoFrame, val ptsMs: Long) : CueFrameResult

    /** This cue cannot be captured (HDR without tone-mapping, undecodable GOP, ...). Not an error; the cue is not asked for again this session. */
    data object Skip : CueFrameResult

    /** The source cannot work (decoder unavailable, unsupported container, ...). Full capture stops for this session and the contributor continues as [ContributionMode.WhileWatching]. */
    data class Fatal(val reason: String) : CueFrameResult
}
