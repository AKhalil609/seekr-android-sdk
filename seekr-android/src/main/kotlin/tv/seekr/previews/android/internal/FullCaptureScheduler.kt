package tv.seekr.previews.android.internal

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.seekr.previews.android.CapturedVideoFrame
import tv.seekr.previews.android.CueFrameResult
import tv.seekr.previews.android.CueFrameSource
import tv.seekr.previews.android.PlaybackProbe
import tv.seekr.previews.android.PlaybackSnapshot
import tv.seekr.previews.android.WaitReason

/** Pacing and fallback numbers of [FullCaptureScheduler]; see `docs/full-capture.md`. */
internal data class FullCaptureTiming(
    /** Gap between two extractor calls while the player is paused / playing (completion to start). */
    val pausedIntervalMs: Long = 400,
    val playingIntervalMs: Long = 3_000,
    /** No extractor work this long after a seek / a rebuffering. */
    val seekQuietMs: Long = 5_000,
    val stallQuietMs: Long = 5_000,
    /** The extractor reads the same file over the network: stay out while the player's own buffer is thin. */
    val minBufferedAheadMs: Long = 30_000,
    val callTimeoutMs: Long = 8_000,
    val maxConsecutiveErrors: Int = 3,
    val maxPacingViolations: Int = 2,
    /** How often the pause conditions are looked at again. */
    val pollMs: Long = 500,
    /** How often to look for work when there is none. */
    val idleMs: Long = 1_000,
    /** Frames further than this from the cue are not stored (they would not verify; PACKAGE-SPEC §7.3.5). */
    val maxPtsDriftMs: Long = 3_000,
    /** Beyond this the frame is plainly the wrong place in the file (PACKAGE-SPEC §3 `bad_format` window). */
    val discardPtsDriftMs: Long = 10_000,
)

/**
 * Background capture of every cue still needed, from a [CueFrameSource], while the player is open. One worker coroutine (on a dedicated
 * low-priority thread, supplied by the caller), at most one `frameAt` in flight, paced from [PlaybackSnapshot] so that it never competes with
 * playback. On [CueFrameResult.Fatal], repeated errors/timeouts or pacing violations it stops for good ([fellBack]); the session carries on
 * with the while-watching path. The source is closed once the worker has ended (never while a call is running).
 */
internal class FullCaptureScheduler(
    private val source: CueFrameSource,
    private val probe: PlaybackProbe,
    private val clock: () -> Long,
    private val durationMs: Long,
    private val timing: FullCaptureTiming,
    private val deviceThrottled: () -> Boolean,
) {
    /**
     * The cues still to capture (shared with the session's while-watching path, shrinks as either path captures) and how to store a tile.
     * [store] returns false when the tile was not kept (cue already captured meanwhile, encode failed).
     */
    class Ledger(val remaining: Set<Int>, val store: (cue: Int, ptsMs: Long, frame: CapturedVideoFrame) -> Boolean)

    @Volatile
    var ledger: Ledger? = null

    /** True once full capture gave up for this session. */
    @Volatile
    var fellBack = false
        private set

    /** Why the worker is holding off right now (for the HUD), null while working or idle. */
    @Volatile
    var pauseReason: WaitReason? = null
        private set

    @Volatile
    var fallbackReason: String? = null
        private set

    private var job: Job? = null
    private val closed = AtomicBoolean(false)

    /** Whether the worker coroutine is still alive (tests: no leaked coroutine after stop). */
    val running: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val started = scope.launch(dispatcher) {
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                fallBack("unexpected ${t.javaClass.simpleName}") // full capture must never take the session (or the process) down
            }
        }
        job = started
        // Runs when the worker has really ended (after an in-flight call unwound), or right away if it never started.
        started.invokeOnCompletion { closeSource() }
    }

    /** Cancels without waiting: stopping a session must stay prompt; the source is closed once the in-flight call has unwound. */
    fun cancel() {
        job?.cancel()
    }

    private fun closeSource() {
        if (!closed.compareAndSet(false, true)) return
        try {
            source.close()
        } catch (e: Exception) {
            // nothing to do: the decoder is gone either way
        }
    }

    private fun fallBack(reason: String) {
        fallbackReason = reason
        fellBack = true
        pauseReason = null
    }

    private enum class Hold(val ui: WaitReason?) { Seek(WaitReason.Seeking), Stall(WaitReason.Buffering), LowBuffer(WaitReason.Buffering), Dropped(WaitReason.Buffering), Thermal(null), Protected(null) }

    private fun holdFor(snap: PlaybackSnapshot): Hold? = when {
        snap.isHdr || snap.isSecure -> Hold.Protected
        snap.thermalThrottled || deviceThrottled() -> Hold.Thermal
        snap.msSinceLastSeek < timing.seekQuietMs -> Hold.Seek
        snap.msSinceLastStall < timing.stallQuietMs -> Hold.Stall
        // Near the end the player cannot be 30 s ahead: only require what is left of the file.
        snap.bufferedAheadMs < minOf(timing.minBufferedAheadMs, (durationMs - snap.positionMs - 1_000).coerceAtLeast(0)) -> Hold.LowBuffer
        else -> null
    }

    private suspend fun loop() {
        var lastEndAt: Long? = null
        var droppedBaseline = 0
        var consecutiveErrors = 0
        var violations = 0
        val attempted = HashSet<Int>()
        while (!fellBack) {
            val led = ledger
            if (led == null) {
                pauseReason = null
                delay(timing.idleMs)
                continue
            }
            val snap = snapshotOrNull()
            if (snap == null) {
                delay(timing.pollMs)
                continue
            }
            if (snap.droppedFramesRecent < droppedBaseline) droppedBaseline = snap.droppedFramesRecent
            val dropsRose = snap.droppedFramesRecent > droppedBaseline
            droppedBaseline = snap.droppedFramesRecent
            val hold = if (dropsRose) Hold.Dropped else holdFor(snap)
            if (hold != null) {
                pauseReason = hold.ui
                delay(timing.pollMs)
                continue
            }
            val cue = pick(led.remaining, attempted, snap.positionMs)
            if (cue == null) {
                pauseReason = null
                delay(timing.idleMs)
                continue
            }
            val gap = if (snap.isPlaying) timing.playingIntervalMs else timing.pausedIntervalMs
            val sinceLast = lastEndAt?.let { clock() - it } ?: Long.MAX_VALUE
            if (sinceLast < gap) {
                pauseReason = null
                delay(gap - sinceLast) // then everything above is checked again against a fresh snapshot
                continue
            }

            pauseReason = null
            attempted += cue
            val target = cue * CUE_MS
            val droppedBefore = snap.droppedFramesRecent
            val result = callSource(cue, target)
            lastEndAt = clock()

            when (result) {
                null -> if (++consecutiveErrors >= timing.maxConsecutiveErrors) fallBack("$consecutiveErrors consecutive errors")
                is CueFrameResult.Fatal -> fallBack(result.reason)
                CueFrameResult.Skip -> consecutiveErrors = 0
                is CueFrameResult.Frame ->
                    if (accept(led, cue, target, result)) {
                        consecutiveErrors = 0
                    } else if (++consecutiveErrors >= timing.maxConsecutiveErrors) {
                        fallBack("storing failed")
                    }
            }

            // Pacing violation: the player started dropping frames right after our call. Cumulative, so a flaky device ends up on the cheap path.
            val after = snapshotOrNull()
            if (after != null) {
                if (snap.isPlaying && after.isPlaying && after.droppedFramesRecent > droppedBefore) {
                    if (++violations >= timing.maxPacingViolations) fallBack("pacing violations")
                }
                droppedBaseline = after.droppedFramesRecent // our own violation is not a second pause
            }
        }
    }

    /** True unless storing failed (an exception); dropped/duplicate frames are fine. */
    private fun accept(led: Ledger, cue: Int, target: Long, result: CueFrameResult.Frame): Boolean {
        val pts = result.ptsMs
        // 3 000..10 000 ms away would be silently dropped by the server and > 10 000 ms makes a package bad_format: neither is worth storing.
        if (pts < 0 || abs(pts - target) > timing.maxPtsDriftMs) return true
        if (cue !in led.remaining) return true // the while-watching path got it while we were decoding
        return try {
            led.store(cue, pts, result.frame)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Null on timeout or any failure (all counted as errors). Cancellation of the worker itself propagates. */
    private suspend fun callSource(cue: Int, target: Long): CueFrameResult? = try {
        withTimeoutOrNull(timing.callTimeoutMs) { source.frameAt(cue, target) }
    } catch (e: CancellationException) {
        kotlin.coroutines.coroutineContext.ensureActive()
        null // the source cancelled itself, not us
    } catch (t: Throwable) {
        null
    }

    private fun snapshotOrNull(): PlaybackSnapshot? = try {
        probe.snapshot()
    } catch (e: Exception) {
        null
    }

    /** From just ahead of the playhead to the end, then wrapping from 0. */
    private fun pick(remaining: Set<Int>, attempted: Set<Int>, positionMs: Long): Int? {
        val start = (positionMs.coerceAtLeast(0) / CUE_MS + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var ahead: Int? = null
        var lowest: Int? = null
        for (c in remaining) {
            if (c in attempted) continue
            if (lowest == null || c < lowest) lowest = c
            if (c >= start && (ahead == null || c < ahead)) ahead = c
        }
        return ahead ?: lowest
    }

    private companion object {
        const val CUE_MS = CapturePolicy.CUE_MS
    }
}
