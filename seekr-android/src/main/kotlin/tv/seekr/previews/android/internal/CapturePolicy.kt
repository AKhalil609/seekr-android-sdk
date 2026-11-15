package tv.seekr.previews.android.internal

import kotlin.math.abs
import tv.seekr.previews.android.ContributorConfig
import tv.seekr.previews.android.PlaybackSnapshot
import tv.seekr.previews.android.StopReason
import tv.seekr.previews.android.WaitReason

/** PACKAGE-SPEC §7.3 as pure functions of the player's signals, so the whole rule set is unit-tested without a player. */
internal object CapturePolicy {
    const val CUE_MS = 10_000L

    /** A reason to give up capturing for the rest of this session, or null. HDR and secure are properties of the video; the rest of the device. */
    fun sessionStop(snap: PlaybackSnapshot, config: ContributorConfig, deviceThrottled: Boolean): StopReason? = when {
        snap.isHdr -> StopReason.Hdr
        snap.isSecure -> StopReason.SecureSurface
        snap.thermalThrottled || deviceThrottled -> StopReason.Throttled
        snap.droppedFramesRecent >= config.droppedFramesStop -> StopReason.DroppedFrames
        else -> null
    }

    /** Why a frame may not be taken right now, or null when playing at 1x with no recent seek or stall. */
    fun waitReason(snap: PlaybackSnapshot, config: ContributorConfig): WaitReason? = when {
        !snap.isPlaying -> WaitReason.Paused
        snap.speed != 1f -> WaitReason.NotNormalSpeed
        snap.msSinceLastSeek < config.minMsSinceSeek -> WaitReason.Seeking
        snap.msSinceLastStall < config.minMsSinceStall -> WaitReason.Buffering
        else -> null
    }

    /** The cue whose window `[10 s i, 10 s i + captureWindowMs]` contains [positionMs], or null between windows. */
    fun cueInWindow(positionMs: Long, config: ContributorConfig): Int? {
        if (positionMs < 0) return null
        val cue = positionMs / CUE_MS
        if (cue > Int.MAX_VALUE) return null
        return if (positionMs - cue * CUE_MS <= config.captureWindowMs) cue.toInt() else null
    }

    /** A frame's pts must be known and within [ContributorConfig.maxPtsDriftMs] of the cue, else it would not verify and is not included. */
    fun ptsAcceptable(ptsMs: Long, cue: Int, config: ContributorConfig): Boolean =
        ptsMs >= 0 && abs(ptsMs - cue * CUE_MS) <= config.maxPtsDriftMs
}

/** Exponential backoff 1 min to 6 h (PACKAGE-SPEC §7.3.7), overridden by an explicit Retry-After. Shared by every upload and /needs call. */
internal class Backoff(private val config: ContributorConfig, private val clock: () -> Long) {
    @Volatile
    private var untilMs = 0L
    private var nextDelayMs = config.backoffInitialMs

    fun remainingMs(): Long = (untilMs - clock()).coerceAtLeast(0)
    val active: Boolean get() = remainingMs() > 0

    @Synchronized
    fun failed(retryAfterSec: Int?, floorMs: Long = 0) {
        val delay = if (retryAfterSec != null) {
            retryAfterSec * 1_000L // the server named the time: honour it as is (bounded), do not escalate
        } else {
            nextDelayMs.also { nextDelayMs = minOf(nextDelayMs * 2, config.backoffMaxMs) }
        }
        untilMs = clock() + maxOf(delay, floorMs, MIN_DELAY_MS).coerceAtMost(config.backoffMaxMs)
    }

    @Synchronized
    fun succeeded() {
        untilMs = 0
        nextDelayMs = config.backoffInitialMs
    }

    private companion object {
        const val MIN_DELAY_MS = 1_000L
    }
}
