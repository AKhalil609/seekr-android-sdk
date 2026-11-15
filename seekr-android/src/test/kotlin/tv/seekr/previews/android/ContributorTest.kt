package tv.seekr.previews.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.seekr.previews.android.internal.SessionTiming
import tv.seekr.previews.android.internal.StatsRecorder
import tv.seekr.previews.android.internal.TileCache
import tv.seekr.previews.core.contrib.ContributionError
import tv.seekr.previews.core.contrib.NeedsReason
import tv.seekr.previews.core.contrib.NeedsResult
import tv.seekr.previews.core.contrib.SubmitResult

/** The player as the app would feed it, driven by virtual time. */
private class FakePlayer(private val now: () -> Long) : PlaybackProbe {
    var playing = true
    var speed = 1f
    var hdr = false
    var secure = false
    var dropped = 0
    var thermal = false
    var buffered = 120_000L
    private var basePos = 0L
    private var baseAt = now()
    private var seekAt: Long? = null
    private var stallAt: Long? = null

    fun position(): Long = if (playing) basePos + ((now() - baseAt) * speed).toLong() else basePos
    private fun rebase() {
        basePos = position()
        baseAt = now()
    }

    fun seekTo(ms: Long) { rebase(); basePos = ms; seekAt = now() }
    fun pause() { rebase(); playing = false }
    fun resume() { rebase(); playing = true }
    fun changeSpeed(s: Float) { rebase(); speed = s }
    fun stall() { stallAt = now() }

    override fun snapshot() = PlaybackSnapshot(
        positionMs = position(),
        isPlaying = playing,
        speed = speed,
        msSinceLastSeek = seekAt?.let { now() - it } ?: Long.MAX_VALUE,
        msSinceLastStall = stallAt?.let { now() - it } ?: Long.MAX_VALUE,
        bufferedAheadMs = buffered,
        isHdr = hdr,
        isSecure = secure,
        droppedFramesRecent = dropped,
        thermalThrottled = thermal,
    )
}

private class FakeFrames(private val player: FakePlayer) : FrameSource {
    var available = true
    var ptsOffset = 0L
    var unknownPts = false
    val capturedAt = mutableListOf<Long>()
    var maxSeen = 0 to 0

    override suspend fun capture(maxWidth: Int, maxHeight: Int): CapturedVideoFrame? {
        maxSeen = maxWidth to maxHeight
        if (!available) return null
        val pos = player.position()
        capturedAt += pos
        return CapturedVideoFrame(16, 9, IntArray(144), if (unknownPts) -1 else pos + ptsOffset)
    }
}

class ContributorTest {
    private val nSlots = 60
    private val durationMs = 600_000L

    private inner class Harness(
        val test: TestScope,
        val config: ContributorConfig = ContributorConfig(),
        val dir: File = tempDir(),
        val prefs: FakeSharedPreferences = FakeSharedPreferences(),
        val api: FakeApi = FakeApi(),
    ) {
        val scheduler = test.testScheduler
        val clock = { 10_000_000L + scheduler.currentTime }
        val dispatcher = StandardTestDispatcher(scheduler)
        val player = FakePlayer(clock)
        val frames = FakeFrames(player)
        var networkOk = true
        var deviceThrottled = false
        val cache = TileCache(dir, config.cacheMaxBytes, 30L * 24 * 3600 * 1000, clock)
        val contributor = SeekrContributor(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            api = api,
            cache = cache,
            statsRecorder = StatsRecorder(prefs),
            network = { networkOk },
            thermal = { deviceThrottled },
            config = config,
            clock = clock,
            codec = { fakeJpeg() },
            codecDispatcher = dispatcher,
            timing = SessionTiming(),
        )

        init {
            api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 3, 5), overlap = listOf(7, 25))) }
        }

        val state get() = contributor.state.value

        fun advance(ms: Long) {
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }

        suspend fun start(fp: String = FP, lookup: LookupOutcome = LookupOutcome.NoPreviews) =
            contributor.startSession(MOVIE, durationMs, fp, frames, player, lookup)

        val cues get() = cache.cues(FP)

        init {
            harnesses += this
        }
    }

    private val harnesses = mutableListOf<Harness>()

    /**
     * runTest ends by running the scheduler until idle, and a live session ticks forever: always stop every contributor, even when an
     * assertion fails, or a red test becomes a hung build.
     */
    private fun scenario(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            harnesses.forEach { it.contributor.stop(flush = false) }
        }
    }

    private fun run(config: ContributorConfig = ContributorConfig(), block: suspend Harness.() -> Unit) = scenario {
        Harness(this, config).block()
    }

    // ---- capture policy ----

    @Test
    fun `captures only needed and overlap cues, one frame per cue, in the first second of the cue`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 3, 5), overlap = listOf(7, 40))) }
        start()
        assertEquals(ContributionState.Capturing(0, 5), state)
        advance(95_000)
        assertEquals(setOf(1, 3, 5, 7), cues)
        assertEquals(4, frames.capturedAt.size, "no frame for any cue the server did not ask for")
        for (at in frames.capturedAt) assertTrue(at % 10_000 <= 1_000, "taken at $at")
        assertEquals(listOf(10_000L, 30_000L, 50_000L, 70_000L), frames.capturedAt, "the first frame of the window")
        assertEquals(320 to 180, frames.maxSeen)
        assertEquals(ContributionState.Capturing(4, 5), state)
    }

    @Test
    fun `cached tiles are named with the frame pts`() = run {
        start()
        advance(12_000)
        assertEquals(10_000L, cache.tiles(FP).single().ptsMs)
    }

    @Test
    fun `a seek within 3 s blocks the capture, a seek long enough ago does not`() = run {
        start()
        advance(9_000)
        player.seekTo(10_500) // inside cue 1's window, but just seeked
        advance(500)
        assertEquals(ContributionState.Waiting(WaitReason.Seeking), state)
        advance(5_000)
        assertTrue(cues.isEmpty(), "the window passed while seeking")
        player.seekTo(25_000) // 5 s before cue 3
        advance(5_001)
        assertEquals(setOf(3), cues)
    }

    @Test
    fun `a frame is taken in the window once 3 s have passed since the seek`() = run {
        start()
        player.seekTo(7_100)
        advance(3_000) // position 10_100 and the seek was exactly 3 s ago
        assertEquals(setOf(1), cues)
        assertEquals(10_100L, frames.capturedAt.single())
    }

    @Test
    fun `a stall within 2 s blocks the capture`() = run {
        start()
        advance(9_900)
        player.stall()
        advance(1_300)
        assertEquals(ContributionState.Waiting(WaitReason.Buffering), state)
        advance(500)
        assertTrue(cues.isEmpty(), "window of cue 1 was missed")
        advance(16_300) // position 28_000: a stall 2 s before cue 3
        player.stall()
        advance(2_300)
        assertEquals(setOf(3), cues, "exactly 2 s after the stall is allowed")
    }

    @Test
    fun `paused or not at 1x means no frame`() = run {
        start()
        advance(9_000)
        player.pause()
        advance(10_000)
        assertEquals(ContributionState.Waiting(WaitReason.Paused), state)
        assertTrue(frames.capturedAt.isEmpty())
        player.resume()
        player.seekTo(28_000)
        advance(1_500)
        player.changeSpeed(2f)
        advance(30_000)
        assertEquals(ContributionState.Waiting(WaitReason.NotNormalSpeed), state)
        assertTrue(cues.isEmpty(), "2x playback captures nothing: ${frames.capturedAt}")
        player.changeSpeed(0.5f)
        advance(1_000)
        assertEquals(ContributionState.Waiting(WaitReason.NotNormalSpeed), state)
    }

    @Test
    fun `resuming inside the window still captures it`() = run {
        start()
        advance(9_500)
        player.pause()
        advance(5_000)
        assertTrue(frames.capturedAt.isEmpty())
        player.resume()
        advance(600) // position 10_100, no seek in this session
        assertEquals(setOf(1), cues)
    }

    @Test
    fun `hdr at the start never asks the server`() = run {
        player.hdr = true
        start()
        assertEquals(ContributionState.Stopped(StopReason.Hdr), state)
        assertTrue(api.needsCalls.isEmpty())
    }

    @Test
    fun `a secure surface at the start never asks the server`() = run {
        player.secure = true
        start()
        assertEquals(ContributionState.Stopped(StopReason.SecureSurface), state)
        assertTrue(api.needsCalls.isEmpty())
    }

    @Test
    fun `hdr or secure mid-session ends the capture for the session`() = run {
        start()
        advance(15_000)
        assertEquals(setOf(1), cues)
        player.hdr = true
        advance(300)
        assertEquals(ContributionState.Stopped(StopReason.Hdr), state)
        player.hdr = false
        advance(60_000)
        assertEquals(setOf(1), cues, "stays stopped")
        start()
        player.secure = true
        advance(500)
        assertEquals(ContributionState.Stopped(StopReason.SecureSurface), state)
    }

    @Test
    fun `thermal throttling from the app or the device stops the session`() = run {
        start()
        advance(5_000)
        player.thermal = true
        advance(300)
        assertEquals(ContributionState.Stopped(StopReason.Throttled), state)
        player.thermal = false
        start()
        advance(5_000)
        deviceThrottled = true
        advance(2_300) // the platform status is read every 2 s
        assertEquals(ContributionState.Stopped(StopReason.Throttled), state)
        val seen = frames.capturedAt.size
        advance(40_000)
        assertEquals(seen, frames.capturedAt.size)
    }

    @Test
    fun `rising dropped frames stop the session`() = run {
        start()
        advance(5_000)
        player.dropped = 29
        advance(500)
        assertTrue(state is ContributionState.Capturing)
        player.dropped = 30
        advance(300)
        assertEquals(ContributionState.Stopped(StopReason.DroppedFrames), state)
    }

    @Test
    fun `a frame whose pts drifts more than 3 s from the cue is discarded, up to 3 s is kept`() = run {
        start()
        frames.ptsOffset = 3_500
        advance(12_000)
        assertTrue(frames.capturedAt.isNotEmpty())
        assertTrue(cues.isEmpty(), "drift 3.5 s: not included")
        frames.ptsOffset = 3_000
        advance(20_000)
        assertEquals(setOf(3), cues, "drift exactly 3 s is still fine")
        assertEquals(30_000L + 3_000, cache.tiles(FP).single().ptsMs)
        frames.ptsOffset = -3_001
        advance(20_000)
        assertEquals(setOf(3), cues)
    }

    @Test
    fun `an unknown pts discards the frame`() = run {
        start()
        frames.unknownPts = true
        advance(12_000)
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `a frame source that cannot read the surface just misses the cue`() = run {
        start()
        frames.available = false
        advance(12_000)
        assertTrue(cues.isEmpty())
        assertTrue(state is ContributionState.Capturing)
        frames.available = true
        advance(20_000)
        assertEquals(setOf(3), cues)
    }

    @Test
    fun `the first frame of a window is kept, the cue is not captured twice`() = run {
        start()
        advance(11_500)
        assertEquals(1, frames.capturedAt.size)
    }

    // ---- /needs outcomes ----

    @Test
    fun `an exact lookup never starts a session`() = run {
        start(lookup = LookupOutcome.Exact)
        assertEquals(ContributionState.Finished(FinishReason.HaveExact), state)
        assertTrue(api.needsCalls.isEmpty())
        advance(60_000)
        assertTrue(frames.capturedAt.isEmpty())
    }

    @Test
    fun `needs that say nothing is wanted end the session`() = run {
        for ((reason, finish) in listOf(
            NeedsReason.HaveExact to FinishReason.HaveExact,
            NeedsReason.JobClosed to FinishReason.JobClosed,
            NeedsReason.Ignored to FinishReason.Ignored,
            NeedsReason.BlockedTakedown to FinishReason.BlockedTakedown,
        )) {
            api.needsAnswer = { NeedsResult.Success(needs(nSlots, emptyList(), generate = false, reason = reason)) }
            start()
            assertEquals(ContributionState.Finished(finish), state)
            advance(60_000)
            assertTrue(frames.capturedAt.isEmpty())
        }
    }

    @Test
    fun `nothing needed right now is re-checked after 10 minutes`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, emptyList(), generate = false)) }
        start()
        assertEquals(ContributionState.Finished(FinishReason.NothingNeeded), state)
        advance(9 * 60_000)
        assertEquals(1, api.needsCalls.size)
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(3))) }
        advance(61_000)
        assertEquals(2, api.needsCalls.size)
        assertTrue(state is ContributionState.Capturing, "$state")
    }

    @Test
    fun `not_observed retries needs 60 s apart at most 3 times`() = run {
        api.needsAnswer = { NeedsResult.Failure(ContributionError.NotObserved) }
        start()
        assertEquals(ContributionState.Waiting(WaitReason.BackingOff), state)
        assertEquals(1, api.needsCalls.size)
        advance(59_000)
        assertEquals(1, api.needsCalls.size)
        advance(1_100)
        assertEquals(2, api.needsCalls.size)
        advance(60_000)
        assertEquals(3, api.needsCalls.size)
        advance(60_000)
        assertEquals(4, api.needsCalls.size) // the original plus 3 retries
        assertEquals(ContributionState.Stopped(StopReason.NotObserved), state)
        advance(10 * 60_000)
        assertEquals(4, api.needsCalls.size)
    }

    @Test
    fun `needs on a metered network waits for an unmetered one without touching the network`() = run {
        networkOk = false
        start()
        assertEquals(ContributionState.Waiting(WaitReason.WaitingForNetwork), state)
        advance(65_000)
        assertTrue(api.needsCalls.isEmpty())
        networkOk = true
        advance(31_000)
        assertEquals(1, api.needsCalls.size)
        assertTrue(state is ContributionState.Capturing)
    }

    @Test
    fun `a 403 on needs stops until restart, later sessions make no request`() = run {
        api.needsAnswer = { NeedsResult.Failure(ContributionError.NotEnabled) }
        start()
        assertEquals(ContributionState.Stopped(StopReason.NotAllowed), state)
        start()
        assertEquals(ContributionState.Stopped(StopReason.NotAllowed), state)
        assertEquals(1, api.needsCalls.size)
    }

    @Test
    fun `a malformed fp or duration never reaches the network`() = run {
        start(fp = "nope")
        assertEquals(ContributionState.Stopped(StopReason.Error), state)
        assertTrue(api.needsCalls.isEmpty())
    }

    // ---- upload cadence ----

    private suspend fun Harness.playUntilCue(cue: Int) = advance(cue * 10_000L + 1_100L - scheduler.currentTime)

    @Test
    fun `uploads after every 30 new cues while the player buffer is at least 60 s ahead`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59)) }
        start()
        playUntilCue(28)
        assertEquals(29, cues.size)
        assertTrue(api.submits.isEmpty(), "29 cues buffered")
        playUntilCue(29)
        advance(500)
        assertEquals(1, api.submits.size)
        assertEquals((0..29).toList(), api.submits.single().cues)
        assertEquals(2, api.needsCalls.size, "needs asked at the start and refreshed right before the upload")
        assertTrue(cues.isEmpty(), "uploaded tiles leave the cache")
        playUntilCue(58)
        advance(500)
        assertEquals(1, api.submits.size)
        playUntilCue(59)
        advance(500)
        assertEquals(2, api.submits.size)
        assertEquals((30..59).toList(), api.submits.last().cues)
        assertEquals(ContributionState.Finished(FinishReason.Uploaded), state)
    }

    @Test
    fun `no upload while the player buffer is short, then it goes as soon as the buffer is healthy`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59)) }
        player.buffered = 59_999
        start()
        playUntilCue(34)
        assertTrue(api.submits.isEmpty())
        assertEquals(35, cues.size)
        player.buffered = 60_000
        advance(300)
        assertEquals(1, api.submits.size)
        assertEquals(35, api.submits.single().cues.size)
    }

    @Test
    fun `the servers upload_after_cues overrides the default`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 5)) }
        start()
        playUntilCue(4)
        advance(500)
        assertEquals(1, api.submits.size)
        assertEquals((0..4).toList(), api.submits.single().cues)
    }

    @Test
    fun `a pause longer than 2 minutes flushes what is buffered`() = run {
        start()
        advance(31_500) // cues 1 and 3 captured
        assertEquals(setOf(1, 3), cues)
        player.pause()
        advance(119_000)
        assertTrue(api.submits.isEmpty())
        advance(2_000)
        assertEquals(1, api.submits.size)
        assertEquals(listOf(1, 3), api.submits.single().cues)
        advance(10 * 60_000)
        assertEquals(1, api.submits.size, "one flush per pause")
    }

    @Test
    fun `stop with flush uploads, stop without flush keeps the cache`() = scenario {
        val h = Harness(this)
        h.start()
        h.advance(31_500)
        h.contributor.stop(flush = false)
        assertEquals(setOf(1, 3), h.cues)
        assertTrue(h.api.submits.isEmpty())
        assertEquals(ContributionState.Idle, h.state)

        h.start()
        h.advance(500)
        h.contributor.stop(flush = true)
        assertEquals(1, h.api.submits.size)
        assertEquals(listOf(1, 3), h.api.submits.single().cues)
        assertTrue(h.cues.isEmpty())
        assertEquals(ContributionState.Idle, h.state)
    }

    @Test
    fun `tiles survive a restart and resumePending uploads them`() = scenario {
        val dir = tempDir()
        val prefs = FakeSharedPreferences()
        val first = Harness(this, dir = dir, prefs = prefs)
        first.start()
        first.advance(31_500)
        first.contributor.stop(flush = false)
        assertEquals(setOf(1, 3), first.cues)

        val api = FakeApi().apply { needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 3, 5))) } }
        val second = Harness(this, dir = dir, prefs = prefs, api = api)
        second.contributor.resumePending()
        assertEquals(1, api.submits.size)
        assertEquals(FP, api.submits.single().fp)
        assertEquals(MOVIE, api.submits.single().content, "the envelope is restored from disk")
        assertEquals(durationMs, api.submits.single().durationMs)
        assertEquals(listOf(1, 3), api.submits.single().cues)
        assertTrue(second.cues.isEmpty())
        assertEquals(2L, second.contributor.stats.value.tilesUploaded)
        assertEquals(2L, second.contributor.stats.value.tilesCaptured)
    }

    @Test
    fun `a new session of the same file does not capture cues that are already cached`() = scenario {
        val h = Harness(this)
        h.start()
        h.advance(15_000)
        h.contributor.stop(flush = false)
        assertEquals(setOf(1), h.cues)
        h.player.seekTo(0)
        h.start()
        h.advance(3_000) // the seek above settles
        assertEquals(ContributionState.Capturing(1, 5), h.state)
        h.advance(9_000)
        assertEquals(1, h.frames.capturedAt.count { it in 10_000..11_000 } , "cue 1 not captured again")
        h.contributor.stop(flush = false)
    }

    @Test
    fun `resumePending makes no request on a metered network`() = scenario {
        val h = Harness(this)
        h.start()
        h.advance(15_000)
        h.contributor.stop(flush = false)
        h.networkOk = false
        h.api.needsCalls.clear()
        h.contributor.resumePending()
        assertTrue(h.api.needsCalls.isEmpty() && h.api.submits.isEmpty())
        assertEquals(setOf(1), h.cues)
    }

    @Test
    fun `a metered network at upload time keeps tiles and shows waiting`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 3)) }
        start()
        networkOk = false
        playUntilCue(5)
        assertTrue(api.submits.isEmpty())
        assertEquals(ContributionState.Waiting(WaitReason.WaitingForNetwork), state)
        assertEquals(6, cues.size)
        networkOk = true
        advance(31_000)
        assertEquals(1, api.submits.size)
    }

    // ---- server answers during a session ----

    @Test
    fun `rate limited upload backs off by Retry-After and capture continues`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 3)) }
        api.submitAnswer = { SubmitResult.Failure(ContributionError.RateLimited(retryAfterSec = 300)) }
        start()
        playUntilCue(3)
        advance(500)
        assertEquals(1, api.submits.size)
        playUntilCue(8)
        assertEquals(1, api.submits.size, "no retry inside the Retry-After")
        assertEquals(9, cues.size, "capture continues while backing off")
        assertEquals(ContributionState.Waiting(WaitReason.BackingOff), state)
        api.submitAnswer = { SubmitResult.Failure(ContributionError.RateLimited(retryAfterSec = 300)).let { accepted() } }
        advance(260_000)
        assertEquals(2, api.submits.size)
    }

    @Test
    fun `job_closed on upload finishes the session and drops the cache`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 2)) }
        api.submitAnswer = { SubmitResult.Failure(ContributionError.JobClosed("closed")) }
        start()
        playUntilCue(2)
        advance(500)
        assertEquals(ContributionState.Finished(FinishReason.JobClosed), state)
        assertTrue(cues.isEmpty())
        val seen = frames.capturedAt.size
        advance(60_000)
        assertEquals(seen, frames.capturedAt.size)
    }

    @Test
    fun `422 on upload stops that file and a later session of it makes no request`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 2)) }
        api.submitAnswer = { SubmitResult.Failure(ContributionError.Rejected(422, "unprocessable", null)) }
        start()
        playUntilCue(2)
        advance(500)
        assertEquals(ContributionState.Stopped(StopReason.FileRejected), state)
        val calls = api.needsCalls.size
        start()
        assertEquals(ContributionState.Stopped(StopReason.FileRejected), state)
        assertEquals(calls, api.needsCalls.size)
    }

    @Test
    fun `403 on upload stops everything until restart`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 2)) }
        api.submitAnswer = { SubmitResult.Failure(ContributionError.NotAllowed) }
        start()
        playUntilCue(2)
        advance(500)
        assertEquals(ContributionState.Stopped(StopReason.NotAllowed), state)
        val calls = api.needsCalls.size
        start(fp = FP2)
        assertEquals(ContributionState.Stopped(StopReason.NotAllowed), state)
        assertEquals(calls, api.needsCalls.size)
        assertEquals(2, cues.size, "tiles stay for the next start")
    }

    @Test
    fun `stats count real captures and accepted uploads only`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = 0..59, uploadAfterCues = 3)) }
        frames.ptsOffset = 5_000
        start()
        playUntilCue(4)
        assertEquals(0L, contributor.stats.value.tilesCaptured, "drifted frames are not captures")
        frames.ptsOffset = 0
        playUntilCue(7)
        advance(500)
        val s = contributor.stats.value
        assertEquals(3L, s.tilesCaptured, "cues 5, 6, 7")
        assertEquals(1, s.titlesCaptured)
        assertEquals(3L, s.tilesUploaded)
        assertEquals(1, s.titlesUploaded)
        assertTrue(s.lastUploadAtMs != null)
        contributor.resetStats()
        assertEquals(0L, contributor.stats.value.tilesCaptured)
    }

    @Test
    fun `starting a new session retires the old one and its tiles still go out`() = run {
        start()
        advance(31_500)
        assertEquals(setOf(1, 3), cues)
        player.seekTo(0)
        start(fp = FP2)
        advance(100)
        assertEquals(1, api.submits.count { it.fp == FP }, "old session flushed in the background")
        assertFalse(state is ContributionState.Idle)
    }

    @Test
    fun `finished when everything asked for is captured and uploaded`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 2))) }
        start()
        advance(25_000)
        assertEquals(1, api.submits.size)
        assertEquals(listOf(1, 2), api.submits.single().cues)
        assertEquals(ContributionState.Finished(FinishReason.Uploaded), state)
    }

    @Test
    fun `a refreshed needs that no longer wants a cue stops capturing it`() = run {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 2, 30, 31, 32, 50), uploadAfterCues = 2)) }
        start()
        advance(15_000)
        assertEquals(ContributionState.Capturing(1, 6), state)
        // by the time the upload refreshes /needs, other viewers have covered 31 and 50
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 2, 30, 32), uploadAfterCues = 2)) }
        advance(10_000)
        assertEquals(listOf(1, 2), api.submits.single().cues)
        advance(500)
        assertEquals(ContributionState.Capturing(2, 4), state)
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(30, 32), uploadAfterCues = 2)) }
        advance(320_000)
        assertEquals(listOf(30, 32), api.submits.last().cues, "31 is not captured any more")
    }

    @Test
    fun `clearPending ends the session without uploading and empties the cache but keeps stats`() = scenario {
        val h = Harness(this)
        h.start()
        h.advance(31_500)
        assertEquals(setOf(1, 3), h.cues)
        h.cache.markStopped(FP2, tv.seekr.previews.android.internal.FileMeta(MOVIE, durationMs))
        h.contributor.clearPending()
        assertTrue(h.api.submits.isEmpty(), "nothing is uploaded")
        assertTrue(h.cache.pendingFiles().isEmpty())
        assertTrue(h.cues.isEmpty())
        assertFalse(h.cache.isStopped(FP2), "the remembered stop set is gone too")
        assertEquals(null, h.cache.meta(FP))
        assertEquals(ContributionState.Idle, h.state)
        assertEquals(2L, h.contributor.stats.value.tilesCaptured)
        h.advance(60_000)
        assertEquals(2, h.frames.capturedAt.size, "the session is over")
    }

    @Test
    fun `clearPending works when nothing is cached`() = run {
        contributor.clearPending()
        assertEquals(ContributionState.Idle, state)
        assertTrue(api.needsCalls.isEmpty() && api.submits.isEmpty())
    }
}
