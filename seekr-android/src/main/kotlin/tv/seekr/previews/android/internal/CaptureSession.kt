package tv.seekr.previews.android.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.seekr.previews.android.CapturedVideoFrame
import tv.seekr.previews.android.ContributionMode
import tv.seekr.previews.android.ContributionState
import tv.seekr.previews.android.ContributorConfig
import tv.seekr.previews.android.CueFrameSource
import tv.seekr.previews.android.FinishReason
import tv.seekr.previews.android.FrameSource
import tv.seekr.previews.android.PlaybackProbe
import tv.seekr.previews.android.PlaybackSnapshot
import tv.seekr.previews.android.StopReason
import tv.seekr.previews.android.TileEncoder
import tv.seekr.previews.android.WaitReason
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.contrib.Needs

internal class SessionTiming(
    /** How often the capture loop looks at the player. A cue window is 1 s, so 250 ms gives several chances without waking the CPU needlessly. */
    val tickMs: Long = 250,
    /** `not_observed` retry spacing (DESIGN-v2 §6.1). */
    val needsRetryMs: Long = 60_000,
    val needsRetryMax: Int = 3,
    /** How often the platform thermal status is read (a binder call). */
    val thermalCheckMs: Long = 2_000,
    /** How long a session waits for an unmetered network before looking again. */
    val networkRetryMs: Long = 30_000,
)

/**
 * One playback's capture session. A single coroutine ticks a few times a second: reads the player's [PlaybackSnapshot], applies
 * [CapturePolicy], takes at most one frame per cue from [frameSource] (a small PixelCopy), encodes and caches it, and starts uploads
 * when the cadence says so. State changes go out through [publish]; once [detach]ed the session no longer publishes
 * (a replaced session's late flush must not overwrite the new one's state).
 */
internal class CaptureSession(
    private val scope: CoroutineScope,
    private val config: ContributorConfig,
    private val timing: SessionTiming,
    private val clock: () -> Long,
    private val uploader: Uploader,
    private val cache: TileCache,
    private val stats: StatsRecorder,
    private val thermal: ThermalMonitor,
    private val codec: (CapturedVideoFrame) -> ByteArray?,
    private val codecDispatcher: CoroutineDispatcher,
    val content: SeekrContent,
    val durationMs: Long,
    val fp: String,
    private val frameSource: FrameSource,
    private val probe: PlaybackProbe,
    /** Non-null = FullTitle mode: the session owns it (closes it when it ends or full capture falls back). */
    cueFrames: CueFrameSource? = null,
    private val fullTiming: FullCaptureTiming = FullCaptureTiming(),
    private val fullDispatcher: CoroutineDispatcher = codecDispatcher,
    private val emit: (ContributionState) -> Unit,
) {
    private val full: FullCaptureScheduler? = cueFrames?.let {
        FullCaptureScheduler(it, probe, clock, durationMs, fullTiming.copy(maxPtsDriftMs = config.maxPtsDriftMs)) { deviceThrottled }
    }

    /** Held while a tile is claimed + stored, so the while-watching and the full path never store (or count) the same cue twice. */
    private val storeLock = Any()

    private val meta = FileMeta(content, durationMs)
    private var job: Job? = null
    private val firstAnswer = CompletableDeferred<Unit>()

    @Volatile
    private var detached = false

    @Volatile
    private var pendingDirty = false

    /** Starts the loop; the returned deferred completes once the first /needs attempt has an outcome (or the session ended). */
    fun start(): CompletableDeferred<Unit> {
        full?.start(scope, fullDispatcher)
        job = scope.launch {
            try {
                run()
            } finally {
                full?.cancel()
                firstAnswer.complete(Unit)
            }
        }
        return firstAnswer
    }

    suspend fun cancelAndJoin() {
        full?.cancel() // not joined: a decoder call that is slow to unwind must not delay stop(); the source is closed when it has
        job?.cancel()
        job?.join()
    }

    /** Test hook: the full-capture worker is still alive. */
    internal val fullWorkerRunning: Boolean get() = full?.running == true

    fun detach() {
        detached = true
    }

    /** Uploads what is cached for this file now, if the network and backoff rules allow. */
    suspend fun flush() {
        val pending = cache.cues(fp).size
        if (pending == 0) return
        publish(ContributionState.Uploading(pending))
        when (val step = uploader.upload(fp)) {
            is UploadStep.Blocked -> when (val gate = step.gate) {
                is Gate.Finish -> if (gate.terminal) publish(ContributionState.Finished(gate.reason))
                is Gate.Stop -> publish(ContributionState.Stopped(gate.reason))
                else -> Unit
            }
            else -> Unit
        }
        pendingDirty = true
    }

    private fun publish(state: ContributionState) {
        if (!detached) emit(state)
    }

    // ---- the loop ----

    private suspend fun CoroutineScope.run() {
        var needsTries = 0
        while (isActive) {
            publishIfNotFinished(ContributionState.Checking)
            sessionStop(probe.snapshot())?.let {
                publish(ContributionState.Stopped(it))
                return
            }
            when (val step = uploader.needs(fp, meta)) {
                is NeedsStep.Got -> {
                    needsTries = 0
                    firstAnswer.complete(Unit)
                    when (capture(step.needs)) {
                        Exit.Done -> return
                        Exit.AskAgainLater -> delay(config.needsRefreshMs)
                    }
                }
                is NeedsStep.Blocked -> when (val gate = step.gate) {
                    is Gate.Wait -> {
                        if (!refreshing) publish(ContributionState.Waiting(gate.reason))
                        firstAnswer.complete(Unit)
                        delay(gate.retryInMs.coerceAtLeast(timing.tickMs))
                    }
                    is Gate.Finish -> {
                        publish(ContributionState.Finished(gate.reason))
                        firstAnswer.complete(Unit)
                        if (gate.terminal) return
                        refreshing = true
                        delay(config.needsRefreshMs)
                    }
                    is Gate.Stop -> {
                        publish(ContributionState.Stopped(gate.reason))
                        return
                    }
                    Gate.NeedsLater -> {
                        if (++needsTries > timing.needsRetryMax) {
                            publish(ContributionState.Stopped(StopReason.NotObserved))
                            return
                        }
                        publish(ContributionState.Waiting(WaitReason.BackingOff))
                        firstAnswer.complete(Unit)
                        delay(timing.needsRetryMs)
                    }
                }
            }
        }
    }

    /** True while a finished (nothing needed) file is only being re-checked: the state stays Finished instead of flickering. */
    private var refreshing = false

    private fun publishIfNotFinished(state: ContributionState) {
        if (!refreshing) publish(state)
    }

    private enum class Exit { Done, AskAgainLater }

    private fun sessionStop(snap: PlaybackSnapshot): StopReason? {
        val now = clock()
        if (now - lastThermalCheckMs >= timing.thermalCheckMs || lastThermalCheckMs == Long.MIN_VALUE) {
            lastThermalCheckMs = now
            deviceThrottled = thermal.isThrottled()
        }
        return CapturePolicy.sessionStop(snap, config, deviceThrottled)
    }

    private var lastThermalCheckMs = Long.MIN_VALUE

    @Volatile
    private var deviceThrottled = false

    private suspend fun CoroutineScope.capture(needs: Needs): Exit {
        refreshing = false
        val target = needs.capture
        val cached = cache.cues(fp)
        // Shared with the full-capture worker (another thread): a concurrent set, and every claim goes through storeTile.
        val remaining: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        for (cue in target) if (cue !in cached) remaining += cue
        val captured = AtomicInteger(target.count { it in cached }) // earlier sessions of the same file already hold these
        var pending = cached.size
        val uploadAfter = needs.uploadAfterCues.takeIf { it > 0 } ?: config.uploadAfterCues
        var uploadedAny = false
        var pausedSince = -1L
        var flushedThisPause = false
        var nextUploadTryAt = 0L
        var uploadBlocked: WaitReason? = null
        var needsTries = 0
        var uploadJob: Job? = null
        var uploadResult: UploadStep? = null
        val watchTried = HashSet<Int>() // cues whose played frame was unusable; only matters while the full path may still get them
        fun uploading() = uploadJob?.isActive == true

        /** Claims [cue] (one tile per cue, whichever path gets there first), encodes and caches it. Safe from either thread. */
        fun storeTile(cue: Int, ptsMs: Long, frame: CapturedVideoFrame): Boolean = synchronized(storeLock) {
            if (!remaining.remove(cue)) return false
            val stored = codec(frame)?.also { cache.put(fp, meta, cue, ptsMs, it) } != null
            if (stored) {
                captured.incrementAndGet()
                pendingDirty = true
                stats.tileCaptured(fp)
            }
            stored
        }
        full?.ledger = FullCaptureScheduler.Ledger(remaining) { cue, pts, frame -> storeTile(cue, pts, frame) }
        try {
            while (isActive) {
                val now = clock()
                val snap = probe.snapshot()
                sessionStop(snap)?.let {
                    uploadJob?.cancel()
                    publish(ContributionState.Stopped(it))
                    return Exit.Done
                }
                if (pendingDirty) {
                    pendingDirty = false
                    pending = cache.cues(fp).size
                }

                // Consume the result of the upload started on an earlier tick.
                val result = uploadResult
                if (result != null && !uploading()) {
                    uploadResult = null
                    pending = cache.cues(fp).size
                    when (result) {
                        is UploadStep.Uploaded -> {
                            uploadedAny = true
                            uploadBlocked = null
                            remaining.retainAll { it in result.needs.capture }
                        }
                        is UploadStep.Nothing -> {
                            uploadBlocked = null
                            result.needs?.let { n -> remaining.retainAll { it in n.capture } }
                        }
                        is UploadStep.Blocked -> when (val gate = result.gate) {
                            is Gate.Wait -> {
                                uploadBlocked = gate.reason
                                nextUploadTryAt = now + gate.retryInMs.coerceAtLeast(timing.tickMs)
                            }
                            is Gate.Finish -> {
                                publish(ContributionState.Finished(gate.reason))
                                if (gate.terminal) return Exit.Done
                                refreshing = true
                                return Exit.AskAgainLater
                            }
                            is Gate.Stop -> {
                                publish(ContributionState.Stopped(gate.reason))
                                return Exit.Done
                            }
                            Gate.NeedsLater -> {
                                if (++needsTries > timing.needsRetryMax) {
                                    publish(ContributionState.Stopped(StopReason.NotObserved))
                                    return Exit.Done
                                }
                                nextUploadTryAt = now + timing.needsRetryMs
                            }
                        }
                    }
                    if (result !is UploadStep.Blocked) flushedThisPause = pausedSince >= 0
                }

                if (snap.isPlaying) {
                    pausedSince = -1
                    flushedThisPause = false
                } else if (pausedSince < 0) {
                    pausedSince = now
                }

                val wait = CapturePolicy.waitReason(snap, config)
                val cue = CapturePolicy.cueInWindow(snap.positionMs, config)
                if (wait == null && cue != null && cue in remaining && cue !in watchTried) {
                    val frame = takeFrame()
                    if (frame != null) {
                        if (CapturePolicy.ptsAcceptable(frame.ptsMs, cue, config)) {
                            val stored = withContext(codecDispatcher) { storeTile(cue, frame.ptsMs, frame) }
                            if (stored) pending++
                        } else {
                            watchTried += cue // one tile per cue: a frame we could not use is not retried here ...
                            if (full?.fellBack != false) remaining -= cue // ... and, without a working full path, not at all
                        }
                    }
                }

                // Upload cadence (PACKAGE-SPEC §7.3.7): every uploadAfter cues while the player's buffer is healthy, after a long pause, or when the file is done.
                if (!uploading() && uploadResult == null && pending > 0 && now >= nextUploadTryAt) {
                    val cadence = pending >= uploadAfter && snap.bufferedAheadMs >= config.minBufferedAheadMs
                    val longPause = pausedSince >= 0 && !flushedThisPause && now - pausedSince >= config.flushAfterPauseMs
                    if (cadence || longPause || remaining.isEmpty()) {
                        uploadJob = launch { uploadResult = uploader.upload(fp) }
                    }
                }

                // Under the store lock: a tile the full-capture worker has claimed but not yet cached must not look like "nothing left".
                if (remaining.isEmpty() && pending == 0 && !uploading() && uploadResult == null &&
                    synchronized(storeLock) { remaining.isEmpty() && cache.cues(fp).isEmpty() }
                ) {
                    publish(ContributionState.Finished(if (uploadedAny) FinishReason.Uploaded else FinishReason.NothingNeeded))
                    return if (uploadedAny) Exit.Done else Exit.AskAgainLater.also { refreshing = true }
                }

                // With a working full path the background worker's own holds (seek, buffering, ...) matter, not "the viewer paused".
                val fullOn = full != null && !full.fellBack
                val shownWait = if (fullOn) full?.pauseReason else wait
                publish(
                    when {
                        uploading() -> ContributionState.Uploading(pending)
                        shownWait != null -> ContributionState.Waiting(shownWait)
                        uploadBlocked != null && pending >= uploadAfter -> ContributionState.Waiting(uploadBlocked)
                        else -> {
                            val done = captured.get()
                            ContributionState.Capturing(
                                done, done + remaining.size,
                                if (fullOn) ContributionMode.FullTitle else ContributionMode.WhileWatching,
                                fellBack = full?.fellBack == true,
                            )
                        }
                    },
                )
                delay(timing.tickMs)
            }
            return Exit.Done
        } finally {
            full?.ledger = null
        }
    }

    private suspend fun takeFrame(): CapturedVideoFrame? = try {
        frameSource.capture(TileEncoder.TILE_WIDTH, TileEncoder.TILE_HEIGHT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // a failing surface must never take playback or the session down
    }
}
