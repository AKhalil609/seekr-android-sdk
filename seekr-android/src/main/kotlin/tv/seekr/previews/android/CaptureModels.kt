package tv.seekr.previews.android

/** One captured, still-raw video frame. [argb] is row-major ARGB_8888 of size [width] x [height] (any size/aspect). */
class CapturedVideoFrame(val width: Int, val height: Int, val argb: IntArray, val ptsMs: Long) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive" }
        require(argb.size >= width * height) { "argb holds ${argb.size} pixels, expected ${width * height}" }
    }
}

/** Where frames come from. The implementation takes at most one frame per call and never blocks the main thread. */
interface FrameSource {
    /**
     * The currently presented frame at most [maxWidth] x [maxHeight] (scale down preserving aspect), or null when it cannot be read now
     * (surface not drawn, secure/protected, copy failed). [CapturedVideoFrame.ptsMs] is the frame's presentation timestamp, or -1 when
     * unknown (then the tile is not used).
     */
    suspend fun capture(maxWidth: Int, maxHeight: Int): CapturedVideoFrame?
}

/**
 * Tracks the presentation timestamp of the most recently rendered frame. The app feeds it from ExoPlayer's VideoFrameMetadataListener
 * (`onVideoFrameAboutToBeRendered(presentationTimeUs, …)`); the SDK has no media3 dependency. Thread-safe.
 */
class FramePtsTracker {
    @Volatile
    private var ptsMs: Long = -1L

    /** Call for every frame about to be rendered; cheap (one volatile write). */
    fun onVideoFrame(presentationTimeUs: Long) {
        ptsMs = if (presentationTimeUs < 0) -1L else presentationTimeUs / 1_000L
    }

    /** Presentation timestamp (ms) of the last frame, -1 until the first frame. */
    val lastPtsMs: Long get() = ptsMs
}

/**
 * Live signals the capture policy needs, supplied by the app (the SDK has no player dependency).
 */
data class PlaybackSnapshot(
    val positionMs: Long,
    val isPlaying: Boolean,
    /** 1.0f = normal. */
    val speed: Float,
    /** [Long.MAX_VALUE] if none this session. */
    val msSinceLastSeek: Long,
    /** Rebuffering; [Long.MAX_VALUE] if none. */
    val msSinceLastStall: Long,
    /** The player's own network buffer. */
    val bufferedAheadMs: Long,
    /** HDR10/HLG/Dolby Vision video: never capture. */
    val isHdr: Boolean,
    /** Protected surface. */
    val isSecure: Boolean,
    /** Frames dropped in the last ~10 s. */
    val droppedFramesRecent: Int,
    val thermalThrottled: Boolean,
)

/** The app's view of the player. Called from the contributor's own thread, a few times a second: keep it cheap and thread-safe. */
fun interface PlaybackProbe {
    fun snapshot(): PlaybackSnapshot
}
