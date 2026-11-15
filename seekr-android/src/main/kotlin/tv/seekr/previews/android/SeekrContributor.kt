package tv.seekr.previews.android

import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import tv.seekr.previews.android.internal.AndroidNetworkGate
import tv.seekr.previews.android.internal.AndroidThermalMonitor
import tv.seekr.previews.android.internal.Backoff
import tv.seekr.previews.android.internal.CapturePolicy
import tv.seekr.previews.android.internal.CaptureSession
import tv.seekr.previews.android.internal.ClientApi
import tv.seekr.previews.android.internal.ContributionApi
import tv.seekr.previews.android.internal.FileInstallationStore
import tv.seekr.previews.android.internal.FullCaptureTiming
import tv.seekr.previews.android.internal.Gate
import tv.seekr.previews.android.internal.NetworkGate
import tv.seekr.previews.android.internal.SessionTiming
import tv.seekr.previews.android.internal.StatsRecorder
import tv.seekr.previews.android.internal.ThermalMonitor
import tv.seekr.previews.android.internal.TileCache
import tv.seekr.previews.android.internal.UploadStep
import tv.seekr.previews.android.internal.Uploader
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews
import tv.seekr.previews.core.contrib.ContributionClient

/** Tunables of [SeekrContributor]; the defaults are PACKAGE-SPEC §7.3. */
data class ContributorConfig(
    val cacheMaxBytes: Long = 20L * 1024 * 1024,
    val cacheMaxAgeDays: Int = 30,
    /** First frame in `[10 s i, 10 s i + 1 s]`. */
    val captureWindowMs: Long = 1_000,
    val maxPtsDriftMs: Long = 3_000,
    val minMsSinceSeek: Long = 3_000,
    val minMsSinceStall: Long = 2_000,
    /** Overridden by the server's `upload_after_cues`. */
    val uploadAfterCues: Int = 30,
    val minBufferedAheadMs: Long = 60_000,
    val flushAfterPauseMs: Long = 120_000,
    val backoffInitialMs: Long = 60_000,
    val backoffMaxMs: Long = 6L * 60 * 60 * 1000,
    /** Ask /needs again this long after an Ok with nothing needed. */
    val needsRefreshMs: Long = 10L * 60 * 1000,
    val droppedFramesStop: Int = 30,
)

enum class LookupOutcome { NoPreviews, Inexact, Exact, Unknown }

/** [Unmetered] = upload (and ask /needs) only on unmetered networks (default, PACKAGE-SPEC §5). */
enum class NetworkPolicy { Unmetered, Any }

sealed interface ContributionState {
    /** No session. */
    data object Idle : ContributionState

    /** Asking /needs. */
    data object Checking : ContributionState

    /** [targetCues] = |needed ∪ overlap| of the session (shrinks when a refreshed /needs drops cues). */
    data class Capturing(
        val capturedCues: Int,
        val targetCues: Int,
        /** The mode in effect: [ContributionMode.FullTitle] only while background full capture is working. */
        val mode: ContributionMode = ContributionMode.WhileWatching,
        /** Full-title capture was requested but gave up this session (decoder fatal, repeated errors, pacing violations): now while-watching only. */
        val fellBack: Boolean = false,
    ) : ContributionState {
        /** 0.5.x signature. */
        constructor(capturedCues: Int, targetCues: Int) : this(capturedCues, targetCues, ContributionMode.WhileWatching, false)
    }

    /** A capture condition does not hold right now. */
    data class Waiting(val reason: WaitReason) : ContributionState

    data class Uploading(val cues: Int) : ContributionState

    /** Nothing (more) to do for this file. */
    data class Finished(val reason: FinishReason) : ContributionState

    /** Gave up (see the error policy). */
    data class Stopped(val reason: StopReason) : ContributionState
}

enum class WaitReason { Paused, Seeking, Buffering, NotNormalSpeed, WaitingForNetwork, BackingOff }
enum class FinishReason { NothingNeeded, HaveExact, JobClosed, Ignored, BlockedTakedown, Uploaded }
enum class StopReason { Hdr, SecureSurface, Throttled, DroppedFrames, FileRejected, NotAllowed, SignedOut, Disabled, NotObserved, Error, UserStopped }

data class ContributionStats(
    /** Distinct files with >= 1 captured tile. */
    val titlesCaptured: Int,
    /** Distinct files with >= 1 accepted upload. */
    val titlesUploaded: Int,
    /** Captured cues * 10 s / 60, floor. */
    val minutesCaptured: Long,
    val minutesUploaded: Long,
    val tilesCaptured: Long,
    val tilesUploaded: Long,
    val bytesUploaded: Long,
    val lastUploadAtMs: Long?,
)

/**
 * Community thumbnail contributions: captures one frame per 10 s cue of what the viewer is actually watching, for the cues the server
 * asks for, and uploads them in the background of the app process (no WorkManager, no service).
 *
 * With [ContributionMode.FullTitle] it also decodes the other needed cues through an app-supplied [CueFrameSource], paced so playback is never affected.
 *
 * Cost on weak devices: one small `PixelCopy` (<= 320x180) and one JPEG encode per 10 s, on a low-priority thread; nothing runs while the
 * session is stopped. Capture is skipped for HDR and secure surfaces, and the session stops on thermal throttling or dropped frames.
 * Network is only touched on unmetered networks (with the default [NetworkPolicy]); tiles wait in a bounded disk cache until then.
 */
class SeekrContributor internal constructor(
    private val scope: CoroutineScope,
    private val api: ContributionApi,
    private val cache: TileCache,
    private val statsRecorder: StatsRecorder,
    private val network: NetworkGate,
    private val thermal: ThermalMonitor,
    private val config: ContributorConfig,
    private val clock: () -> Long,
    private val codec: (CapturedVideoFrame) -> ByteArray?,
    private val codecDispatcher: CoroutineDispatcher,
    private val timing: SessionTiming,
    private val fullTiming: FullCaptureTiming = FullCaptureTiming(),
    /** The dedicated low-priority single thread of full-title capture. */
    private val fullDispatcher: CoroutineDispatcher = codecDispatcher,
) {
    private val backoff = Backoff(config, clock)
    private val uploader = Uploader(api, cache, statsRecorder, network, backoff, config, clock, timing.networkRetryMs)
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow<ContributionState>(ContributionState.Idle)
    private var session: CaptureSession? = null

    /** Live state for a HUD indicator. */
    val state: StateFlow<ContributionState> get() = mutableState

    /** Local, persisted statistics (SharedPreferences-backed; never sent anywhere). */
    val stats: StateFlow<ContributionStats> get() = statsRecorder.stats

    /**
     * Starts (or replaces) the capture session for one playback. Asks /needs; when `generate` is false the session ends in a [ContributionState.Finished] state.
     * Capturing runs in the contributor's own coroutine scope until [stop]. [lookup] is the preview lookup outcome of this playback and gates everything:
     * [LookupOutcome.Exact] never starts a session.
     *
     * [mode] = [ContributionMode.FullTitle] with a [cueFrames] source additionally captures every needed cue in the background while the player is open
     * (see `docs/full-capture.md`); without a source it behaves as [ContributionMode.WhileWatching]. The SDK takes ownership of [cueFrames] and
     * closes it exactly once: when the session ends, when full capture falls back, or immediately when it is not used.
     */
    suspend fun startSession(
        content: SeekrContent,
        durationMs: Long,
        fp: String,
        frameSource: FrameSource,
        playback: PlaybackProbe,
        lookup: LookupOutcome = LookupOutcome.Unknown,
        mode: ContributionMode = ContributionMode.WhileWatching,
        cueFrames: CueFrameSource? = null,
    ) {
        // The SDK owns [cueFrames] from here on: it is closed when the session ends, when full capture falls back, or right now if unused.
        val fullSource = cueFrames.takeIf { mode == ContributionMode.FullTitle }
        if (fullSource == null) closeQuietly(cueFrames)
        val answered = lifecycle.withLock {
            retire(session, flush = true)
            session = null
            mutableState.value = ContributionState.Idle
            if (lookup == LookupOutcome.Exact) {
                closeQuietly(fullSource)
                mutableState.value = ContributionState.Finished(FinishReason.HaveExact)
                return
            }
            if (durationMs <= 0 || !cache.isValidFp(fp)) {
                closeQuietly(fullSource)
                mutableState.value = ContributionState.Stopped(StopReason.Error)
                return
            }
            uploader.halted?.let {
                closeQuietly(fullSource)
                mutableState.value = ContributionState.Stopped(it)
                return
            }
            if (cache.isStopped(fp)) {
                closeQuietly(fullSource)
                mutableState.value = ContributionState.Stopped(StopReason.FileRejected)
                return
            }
            val snap = playback.snapshot()
            val deviceThrottled = thermal.isThrottled()
            CapturePolicy.sessionStop(snap, config, deviceThrottled)?.let {
                closeQuietly(fullSource)
                mutableState.value = ContributionState.Stopped(it)
                return
            }
            mutableState.value = ContributionState.Checking
            val created = CaptureSession(
                scope, config, timing, clock, uploader, cache, statsRecorder, thermal, codec, codecDispatcher,
                content, durationMs, fp, frameSource, playback, fullSource, fullTiming, fullDispatcher,
            ) { mutableState.value = it }
            session = created
            created.start()
        }
        answered.await()
    }

    /**
     * Ends the session. With [flush] the buffered tiles are uploaded first (best effort, respects network/backoff rules; otherwise they stay cached and resume next start).
     */
    suspend fun stop(flush: Boolean = true) {
        lifecycle.withLock {
            val current = session
            session = null
            if (current != null) {
                current.cancelAndJoin()
                if (flush && uploader.halted == null) current.flush()
                current.detach()
            }
            mutableState.value = ContributionState.Idle
        }
    }

    /** Uploads whatever is buffered now (if the network rules allow). */
    suspend fun flush() {
        val current = lifecycle.withLock { session }
        if (current != null && uploader.halted == null) current.flush()
    }

    /** Re-tries cached files from earlier sessions on the next start; call once at app start (cheap when nothing is cached). */
    suspend fun resumePending() {
        cache.purge()
        val active = lifecycle.withLock { session?.fp }
        for (fp in cache.pendingFiles()) {
            if (fp == active) continue
            // Stop at the first thing that says "not now" or "never": every other file would hit the same wall.
            val gate = (uploader.upload(fp) as? UploadStep.Blocked)?.gate ?: continue
            val fileOnly = gate is Gate.Finish || gate == Gate.NeedsLater || (gate is Gate.Stop && gate.reason == StopReason.FileRejected)
            if (!fileOnly) return
        }
    }

    /**
     * For a user opting out of contributing: ends any session without uploading, then deletes every cached tile, its metadata and the
     * remembered rejected files. Statistics ([resetStats] is separate) and the installation token are kept. Safe when nothing is cached.
     */
    suspend fun clearPending() {
        stop(flush = false)
        cache.clear()
    }

    fun resetStats() = statsRecorder.reset()

    private fun closeQuietly(source: CueFrameSource?) {
        try {
            source?.close()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    /** Detaches [old] and lets it upload what it buffered in the background (it respects the same rules); never blocks the new session. */
    private suspend fun retire(old: CaptureSession?, flush: Boolean) {
        old ?: return
        old.cancelAndJoin()
        old.detach()
        if (flush && uploader.halted == null) scope.launch { old.flush() }
    }

    companion object {
        /**
         * @param appVersion e.g. `"seekr-tv/1.4.0"`; goes into `app_version`.
         */
        fun create(
            context: Context,
            auth: SeekrAuth,
            appVersion: String,
            baseUrl: String = SeekrPreviews.DEFAULT_BASE_URL,
            httpClient: OkHttpClient = OkHttpClient(),
            config: ContributorConfig = ContributorConfig(),
            network: NetworkPolicy = NetworkPolicy.Unmetered,
            clock: () -> Long = System::currentTimeMillis,
        ): SeekrContributor {
            val app = context.applicationContext
            val client = ContributionClient.create(
                auth, baseUrl, httpClient,
                FileInstallationStore(File(app.filesDir, "seekr-contrib-installation")),
            )
            // One low-priority daemon thread for the loop, the JPEG encode and the cache I/O: it must never compete with the video pipeline.
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "seekr-contrib").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            // Full-title capture gets its own thread (THREAD_PRIORITY_BACKGROUND, parallelism 1): a slow decoder must never delay the capture loop or uploads.
            // The executor starts its thread on first use, so WhileWatching sessions never pay for it.
            val fullExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(
                    {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        } catch (e: Exception) {
                            // keep the default priority
                        }
                        r.run()
                    },
                    "seekr-full-capture",
                ).apply { isDaemon = true }
            }
            return SeekrContributor(
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                api = ClientApi(client, appVersion),
                cache = TileCache(
                    File(app.cacheDir, "seekr-contrib"),
                    config.cacheMaxBytes,
                    TimeUnit.DAYS.toMillis(config.cacheMaxAgeDays.toLong()),
                    clock,
                ),
                statsRecorder = StatsRecorder(app.getSharedPreferences(StatsRecorder.PREFS_NAME, Context.MODE_PRIVATE)),
                network = AndroidNetworkGate(app, network),
                thermal = AndroidThermalMonitor(app),
                config = config,
                clock = clock,
                codec = { TileEncoder.encode(it) },
                codecDispatcher = dispatcher,
                timing = SessionTiming(),
                fullDispatcher = fullExecutor.asCoroutineDispatcher(),
            )
        }
    }
}
