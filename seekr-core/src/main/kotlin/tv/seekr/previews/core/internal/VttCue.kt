package tv.seekr.previews.core.internal

import tv.seekr.previews.core.SeekrTile

/** One parsed WebVTT cue: a time window mapped to a sprite tile. Internal to the SDK. */
internal data class VttCue(
    val startMs: Long,
    val endMs: Long,
    val tile: SeekrTile,
)
