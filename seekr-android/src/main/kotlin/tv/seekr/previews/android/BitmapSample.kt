package tv.seekr.previews.android

import android.graphics.Bitmap

/**
 * One frame grabbed from the video being played, with the playback position it came from.
 *
 * [playbackMs] must be your player's position for that frame — the same clock you pass to
 * [SeekrTrack.thumbnailAt]. The offset being measured *is* the difference between that clock
 * and the preview timeline, so a frame timestamped on any other basis yields a confidently
 * wrong answer rather than no answer.
 *
 * See [SeekrTrack.matchOffset] for how many frames to grab, how to space them, and what size
 * to ask your player for.
 */
class BitmapFrame(val bitmap: Bitmap, val playbackMs: Long)

/**
 * A **burst**: several [BitmapFrame]s taken close together, spread across one cue interval.
 *
 * The burst, not the individual frame, is the unit of evidence — see [SeekrTrack.matchOffset]
 * for why, and for the measurements behind "four frames".
 */
class BitmapSample(val frames: List<BitmapFrame>) {
    constructor(vararg frames: BitmapFrame) : this(frames.toList())
}
