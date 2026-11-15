package tv.seekr.previews.android.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.seekr.previews.android.ContributorConfig
import tv.seekr.previews.android.FinishReason
import tv.seekr.previews.android.StopReason
import tv.seekr.previews.android.WaitReason
import tv.seekr.previews.core.contrib.ContributionError
import tv.seekr.previews.core.contrib.ErrorPolicy
import tv.seekr.previews.core.contrib.Needs
import tv.seekr.previews.core.contrib.NeedsReason
import tv.seekr.previews.core.contrib.NeedsResult
import tv.seekr.previews.core.contrib.NvmtWriter
import tv.seekr.previews.core.contrib.SubmitResult
import tv.seekr.previews.core.contrib.policy

/** Why a network step did not produce its result; the session turns it into a [tv.seekr.previews.android.ContributionState]. */
internal sealed interface Gate {
    /** Not now: wrong network, backing off. Try again after [retryInMs]. */
    data class Wait(val reason: WaitReason, val retryInMs: Long) : Gate

    /** Nothing (more) to do for this file. [terminal] false = `Ok` with nothing needed *right now*: ask /needs again later. */
    data class Finish(val reason: FinishReason, val terminal: Boolean) : Gate

    data class Stop(val reason: StopReason) : Gate

    /** `not_observed`: ask /needs again in 60 s, at most 3 times. */
    data object NeedsLater : Gate
}

internal sealed interface NeedsStep {
    data class Got(val needs: Needs) : NeedsStep
    data class Blocked(val gate: Gate) : NeedsStep
}

internal sealed interface UploadStep {
    /** Nothing cached was worth sending ([needs] is the refreshed answer when one was obtained). */
    data class Nothing(val needs: Needs?) : UploadStep
    data class Uploaded(val tiles: Int, val needs: Needs) : UploadStep
    data class Blocked(val gate: Gate) : UploadStep
}

/**
 * Every network step of the contributor: `/needs` and the segment upload. It owns the rules that make them safe to call at any time:
 * the network gate, the shared [backoff], and the mapping of each [ContributionError] through its [ErrorPolicy] (PACKAGE-SPEC §7.3.8).
 */
internal class Uploader(
    private val api: ContributionApi,
    private val cache: TileCache,
    private val stats: StatsRecorder,
    private val network: NetworkGate,
    val backoff: Backoff,
    private val config: ContributorConfig,
    private val clock: () -> Long,
    private val networkRetryMs: Long,
) {
    private val mutex = Mutex()

    private enum class Op { Needs, Submit }

    @Volatile
    private var backoffCause: Op? = null

    /** `StopUntilRestart` outcome (403 family): nothing else is attempted for the life of this process. */
    @Volatile
    var halted: StopReason? = null
        private set

    suspend fun needs(fp: String, meta: FileMeta): NeedsStep {
        gate()?.let { return NeedsStep.Blocked(it) }
        return when (val r = api.needs(meta.content, meta.durationMs, fp)) {
            is NeedsResult.Failure -> NeedsStep.Blocked(onError(r.error, fp, meta, Op.Needs))
            is NeedsResult.Success -> {
                // A working /needs says nothing about a failing PUT (e.g. a 503 server_busy on uploads only): only clear a backoff /needs itself caused.
                if (backoffCause == Op.Needs) backoff.succeeded()
                val n = r.needs
                if (n.generate) NeedsStep.Got(n) else NeedsStep.Blocked(onNothingToGenerate(n, fp, meta))
            }
        }
    }

    /**
     * Sends what is cached for [fp]: refreshes /needs first (what is wanted changes while the viewer watches), keeps only cached cues
     * still in `needed ∪ overlap`, then submits segments of at most [NvmtWriter.MAX_PACKAGE_BYTES].
     */
    suspend fun upload(fp: String): UploadStep = mutex.withLock {
        val meta = cache.meta(fp) ?: return UploadStep.Nothing(null)
        if (cache.isStopped(fp)) return UploadStep.Blocked(Gate.Stop(StopReason.FileRejected))
        if (cache.tiles(fp).isEmpty()) return UploadStep.Nothing(null)
        val needs = when (val s = needs(fp, meta)) {
            is NeedsStep.Blocked -> return UploadStep.Blocked(s.gate)
            is NeedsStep.Got -> s.needs
        }
        val wanted = needs.capture
        val all = cache.tiles(fp)
        val (keep, stale) = all.partition { it.cue in wanted && it.cue < needs.nSlots }
        if (stale.isNotEmpty()) cache.remove(fp, stale.map { it.cue })
        if (keep.isEmpty()) return UploadStep.Nothing(needs)

        var sent = 0
        var index = 0
        while (index < keep.size) {
            val writer = NvmtWriter(needs.nSlots)
            val batch = ArrayList<CachedTile>()
            var size = NVMT_HEADER_BYTES
            while (index < keep.size) {
                val tile = keep[index]
                val bytes = try {
                    tile.read()
                } catch (e: java.io.IOException) {
                    index++
                    continue
                }
                if (batch.isNotEmpty() && size + NVMT_RECORD_BYTES + bytes.size > NvmtWriter.MAX_PACKAGE_BYTES) break
                index++
                try {
                    writer.add(tile.cue, tile.ptsMs, bytes)
                } catch (e: IllegalArgumentException) {
                    cache.remove(fp, listOf(tile.cue)) // a corrupt or out-of-range tile would fail every future upload
                    continue
                }
                batch += tile
                size += NVMT_RECORD_BYTES + bytes.size
            }
            if (batch.isEmpty()) continue
            val nvmt = writer.build()
            when (val r = api.submit(meta.content, meta.durationMs, fp, nvmt)) {
                is SubmitResult.Accepted -> {
                    backoff.succeeded()
                    cache.remove(fp, batch.map { it.cue })
                    // A duplicate is bytes the server already has (e.g. a crash after a first accept): not a new contribution.
                    if (!r.submission.duplicate) stats.uploaded(fp, batch.size, nvmt.size.toLong(), clock())
                    sent += batch.size
                }
                is SubmitResult.DryRun -> return UploadStep.Blocked(Gate.Stop(StopReason.Error)) // never requested
                is SubmitResult.Failure -> return UploadStep.Blocked(onError(r.error, fp, meta, Op.Submit))
            }
        }
        if (sent > 0) UploadStep.Uploaded(sent, needs) else UploadStep.Nothing(needs)
    }

    private fun gate(): Gate? {
        halted?.let { return Gate.Stop(it) }
        if (!network.isAllowed()) return Gate.Wait(WaitReason.WaitingForNetwork, networkRetryMs)
        if (backoff.active) return Gate.Wait(WaitReason.BackingOff, backoff.remainingMs())
        return null
    }

    private fun onNothingToGenerate(n: Needs, fp: String, meta: FileMeta): Gate = when (n.reason) {
        NeedsReason.HaveExact -> closed(fp, FinishReason.HaveExact)
        NeedsReason.Ignored -> closed(fp, FinishReason.Ignored)
        NeedsReason.BlockedTakedown -> closed(fp, FinishReason.BlockedTakedown)
        NeedsReason.JobClosed -> closed(fp, FinishReason.JobClosed)
        NeedsReason.JobCap -> {
            cache.markStopped(fp, meta)
            Gate.Stop(StopReason.FileRejected)
        }
        NeedsReason.NotObserved -> Gate.NeedsLater
        // Ok with nothing needed now, or a reason from a newer server: do not capture, ask again later.
        NeedsReason.Ok, NeedsReason.Unknown -> Gate.Finish(FinishReason.NothingNeeded, terminal = false)
    }

    private fun closed(fp: String, reason: FinishReason): Gate {
        cache.drop(fp)
        return Gate.Finish(reason, terminal = true)
    }

    private fun onError(error: ContributionError, fp: String, meta: FileMeta, op: Op): Gate = when (error.policy) {
        ErrorPolicy.RetryAfter -> {
            val retryAfter = when (error) {
                is ContributionError.RateLimited -> error.retryAfterSec
                is ContributionError.ServerBusy -> error.retryAfterSec
                else -> null
            }
            // The error carries no value for installation_too_new: the server wants >= 10 minutes.
            val floor = if (error == ContributionError.InstallationTooNew) INSTALLATION_TOO_NEW_MS else 0L
            backoff.failed(retryAfter, floor)
            backoffCause = op
            Gate.Wait(WaitReason.BackingOff, backoff.remainingMs())
        }
        ErrorPolicy.RetryNeedsLater -> Gate.NeedsLater
        ErrorPolicy.DropFileCache -> closed(fp, FinishReason.JobClosed)
        ErrorPolicy.StopFile -> {
            cache.markStopped(fp, meta)
            Gate.Stop(StopReason.FileRejected)
        }
        ErrorPolicy.StopUntilRestart -> {
            val reason = if (error == ContributionError.Disabled) StopReason.Disabled else StopReason.NotAllowed
            halted = reason
            Gate.Stop(reason)
        }
        // The next playback asks again (one request, none while the bearer returns no token): a sign-in change needs no signal from the app.
        ErrorPolicy.StopUntilSignInChanges -> Gate.Stop(StopReason.SignedOut)
        ErrorPolicy.StopSession -> Gate.Stop(StopReason.Error)
    }

    private companion object {
        const val NVMT_HEADER_BYTES = 20
        const val NVMT_RECORD_BYTES = 12
        const val INSTALLATION_TOO_NEW_MS = 10L * 60 * 1000
    }
}
