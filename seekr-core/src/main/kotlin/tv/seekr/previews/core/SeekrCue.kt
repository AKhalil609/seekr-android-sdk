package tv.seekr.previews.core

/**
 * A [SeekrTile] together with the resolved cue window it was drawn from.
 *
 * [PreviewTrack.tileAt] discards the cue's time window, so a caller has no way to know that
 * the thumbnail it got back actually represents `[startMs, endMs)`, not the exact position it
 * asked for. [PreviewTrack.cueAt] returns this instead so a player can, if it chooses, seek to
 * [startMs] rather than the originally requested position — making the preview and the frame
 * played back agree exactly. See [PreviewTrack.cueAt] for the full trade-off.
 */
data class SeekrCue(
    val tile: SeekrTile,
    val startMs: Long,
    val endMs: Long,
)
