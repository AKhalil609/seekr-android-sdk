package tv.seekr.previews.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.seekr.previews.android.internal.FileMeta
import tv.seekr.previews.android.internal.SessionTiming
import tv.seekr.previews.android.internal.StatsRecorder
import tv.seekr.previews.android.internal.TileCache
import tv.seekr.previews.core.contrib.NeedsResult

private class FtPlayer(private val now: () -> Long) : PlaybackProbe {
    var playing = false
    var buffered = 120_000L
    var dropped = 0
    var stalled = false
    private var basePos = 0L
    private var baseAt = now()
    private var seekAt: Long? = null

    fun position(): Long = if (playing) basePos + (now() - baseAt) else basePos
    fun seekTo(ms: Long) { basePos = ms; baseAt = now(); seekAt = now() }
    fun play(p: Boolean) { basePos = position(); baseAt = now(); playing = p }

    override fun snapshot() = PlaybackSnapshot(
        positionMs = position(), isPlaying = playing, speed = 1f,
        msSinceLastSeek = seekAt?.let { now() - it } ?: Long.MAX_VALUE,
        msSinceLastStall = if (stalled) 0 else Long.MAX_VALUE,
        bufferedAheadMs = buffered, isHdr = false, isSecure = false, droppedFramesRecent = dropped, thermalThrottled = false,
    )
}

private class FtFrames(private val player: FtPlayer) : FrameSource {
    val takenAt = mutableListOf<Long>()
    override suspend fun capture(maxWidth: Int, maxHeight: Int): CapturedVideoFrame {
        val pos = player.position()
        takenAt += pos
        return CapturedVideoFrame(16, 9, IntArray(144), pos)
    }
}

private class FtCueSource : CueFrameSource {
    val cues = mutableListOf<Int>()
    var closes = 0
    var handler: suspend (Int) -> CueFrameResult = { cue -> frame(cue) }
    fun frame(cue: Int) = CueFrameResult.Frame(CapturedVideoFrame(16, 9, IntArray(144), cue * 10_000L), cue * 10_000L)
    override suspend fun frameAt(cueIndex: Int, targetMs: Long): CueFrameResult {
        assertEquals(cueIndex * 10_000L, targetMs)
        cues += cueIndex
        return handler(cueIndex)
    }
    override fun close() { closes++ }
}

class FullTitleContributorTest {
    private val nSlots = 60
    private val durationMs = 600_000L

    private inner class Rig(val test: TestScope) {
        val sched = test.testScheduler
        val clock = { 10_000_000L + sched.currentTime }
        val dispatcher = StandardTestDispatcher(sched)
        val job = SupervisorJob()
        val api = FakeApi()
        val player = FtPlayer { sched.currentTime }
        val frames = FtFrames(player)
        val source = FtCueSource()
        val config = ContributorConfig()
        val cache = TileCache(tempDir(), config.cacheMaxBytes, 30L * 24 * 3600 * 1000, clock)
        val prefs = FakeSharedPreferences()
        val contributor = SeekrContributor(
            scope = CoroutineScope(job + dispatcher), api = api, cache = cache, statsRecorder = StatsRecorder(prefs),
            network = { true }, thermal = { false }, config = config, clock = clock,
            codec = { fakeJpeg() }, codecDispatcher = dispatcher, timing = SessionTiming(),
            fullDispatcher = StandardTestDispatcher(sched),
        )
        val state get() = contributor.state.value
        val cues get() = cache.cues(FP)

        init {
            api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 3, 5), overlap = listOf(7, 25))) }
            api.submitAnswer = { accepted() }
        }

        fun advance(ms: Long) { sched.advanceTimeBy(ms); sched.runCurrent() }

        suspend fun start(
            mode: ContributionMode = ContributionMode.FullTitle,
            cueFrames: CueFrameSource? = source,
            lookup: LookupOutcome = LookupOutcome.NoPreviews,
            fp: String = FP,
        ) = contributor.startSession(MOVIE, durationMs, fp, frames, player, lookup, mode, cueFrames)
    }

    private fun scenario(block: suspend Rig.() -> Unit) = runTest {
        val r = Rig(this)
        try {
            r.block()
        } finally {
            r.contributor.stop(flush = false)
        }
    }

    private fun Rig.noLeakedCoroutines() = assertTrue(job.children.none { it.isActive }, "no coroutine outlives the session")

    @Test
    fun `captures every needed cue while the player is only open and paused`() = scenario {
        start()
        advance(5_000)
        assertEquals(0, frames.takenAt.size, "nothing was watched")
        assertEquals(listOf(1, 3, 5, 7, 25), source.cues.sorted())
        assertEquals(listOf(1, 3, 5, 7, 25), api.submits.single().cues, "all five uploaded once the file is complete")
        assertEquals(ContributionState.Finished(FinishReason.Uploaded), state)
        assertEquals(5, contributor.stats.value.tilesCaptured)
    }

    @Test
    fun `shows Capturing with FullTitle, not Waiting(Paused), while it works in the background`() = scenario {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = (1..30).toList())) }
        start()
        advance(2_000)
        val s = assertIs<ContributionState.Capturing>(state)
        assertEquals(ContributionMode.FullTitle, s.mode)
        assertFalse(s.fellBack)
        assertEquals(30, s.targetCues)
        assertTrue(s.capturedCues in 3..6, "${s.capturedCues}")
    }

    @Test
    fun `a thin buffer shows Waiting(Buffering) and resumes`() = scenario {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = (1..30).toList())) }
        player.buffered = 10_000
        start()
        advance(5_000)
        assertEquals(0, source.cues.size)
        assertEquals(ContributionState.Waiting(WaitReason.Buffering), state)
        player.buffered = 90_000
        advance(2_000)
        assertTrue(source.cues.isNotEmpty())
        assertIs<ContributionState.Capturing>(state)
    }

    @Test
    fun `cues already cached from an earlier session are not decoded again`() = scenario {
        cache.put(FP, FileMeta(MOVIE, durationMs), 3, 30_000, fakeJpeg())
        start()
        advance(5_000)
        assertEquals(listOf(1, 5, 7, 25), source.cues.sorted())
        assertEquals(listOf(1, 3, 5, 7, 25), api.submits.single().cues)
    }

    @Test
    fun `the while-watching path keeps running next to the full path`() = scenario {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 3, 40))) }
        player.buffered = 10_000 // the full path holds off on a thin buffer; the viewer path does not care
        player.play(true)
        start()
        advance(35_000)
        assertEquals(0, source.cues.size)
        assertEquals(setOf(1, 3), cues)
        assertEquals(listOf(10_000L, 30_000L), frames.takenAt)
    }

    @Test
    fun `a cue is stored and counted once whichever path gets it`() = scenario {
        player.play(true)
        start()
        advance(95_000)
        assertEquals(listOf(1, 3, 5, 7, 25), api.submits.single().cues)
        assertEquals(source.cues.size, source.cues.toSet().size, "the full path never decodes a cue twice")
        assertEquals(5L, contributor.stats.value.tilesCaptured, "stats count tiles, not attempts")
    }

    @Test
    fun `a frame the full path loses the race for is dropped, not counted twice`() = scenario {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = listOf(1, 40))) }
        // The viewer plays into cue 1's window while the decoder is busy with it.
        source.handler = { cue ->
            if (cue == 1) {
                player.seekTo(7_000)
                player.play(true)
                kotlinx.coroutines.delay(3_500) // 3 s after the seek the viewer is at 10 s and the while-watching frame lands meanwhile
            }
            source.frame(cue)
        }
        start()
        advance(20_000)
        assertEquals(listOf(1, 40), api.submits.single().cues)
        assertEquals(2L, contributor.stats.value.tilesCaptured)
        assertEquals(1, frames.takenAt.size, "the viewer path took cue 1 while the decoder was still busy with it")
    }

    @Test
    fun `WhileWatching never touches the cue source and closes it`() = scenario {
        player.play(true)
        start(mode = ContributionMode.WhileWatching)
        advance(35_000)
        assertTrue(source.cues.isEmpty())
        assertEquals(1, source.closes)
        assertEquals(setOf(1, 3), cues, "only what was watched")
        val s = assertIs<ContributionState.Capturing>(state)
        assertEquals(ContributionMode.WhileWatching, s.mode)
        assertFalse(s.fellBack)
    }

    @Test
    fun `FullTitle without a cue source behaves as WhileWatching`() = scenario {
        player.play(true)
        start(cueFrames = null)
        advance(35_000)
        assertEquals(setOf(1, 3), cues)
        assertEquals(ContributionMode.WhileWatching, (state as ContributionState.Capturing).mode)
        assertFalse((state as ContributionState.Capturing).fellBack)
    }

    @Test
    fun `Fatal falls back to WhileWatching, closes the source and watching keeps capturing`() = scenario {
        source.handler = { CueFrameResult.Fatal("no decoder") }
        player.play(true)
        start()
        advance(1_500)
        assertEquals(1, source.closes)
        assertEquals(1, source.cues.size)
        advance(35_000)
        assertEquals(1, source.cues.size, "no more decoding")
        assertEquals(setOf(1, 3), cues, "the viewer path still captured what was played")
        val s = assertIs<ContributionState.Capturing>(state)
        assertTrue(s.fellBack)
        assertEquals(ContributionMode.WhileWatching, s.mode)
        assertEquals(1, source.closes)
        contributor.stop(flush = false)
        sched.runCurrent()
        noLeakedCoroutines()
        assertEquals(1, source.closes, "not closed a second time")
    }

    @Test
    fun `three timeouts fall back and the viewer path carries on`() = scenario {
        source.handler = { awaitCancellation() }
        player.play(true)
        start()
        advance(60_000)
        assertEquals(3, source.cues.size)
        assertEquals(1, source.closes)
        val s = assertIs<ContributionState.Capturing>(state)
        assertTrue(s.fellBack)
        assertTrue(cues.containsAll(listOf(1, 3, 5)), "watched cues captured: $cues")
    }

    @Test
    fun `pacing violations fall back`() = scenario {
        api.needsAnswer = { NeedsResult.Success(needs(nSlots, needed = (1..50).toList())) }
        player.play(true)
        source.handler = { cue ->
            player.dropped += 3
            source.frame(cue)
        }
        start()
        advance(30_000)
        assertEquals(2, source.cues.size)
        assertEquals(1, source.closes)
        assertTrue(assertIs<ContributionState.Capturing>(state).fellBack)
    }

    @Test
    fun `stop closes the source once, cancels an in-flight decode and leaves no coroutine`() = scenario {
        source.handler = { awaitCancellation() }
        start()
        advance(1_000)
        assertEquals(1, source.cues.size)
        contributor.stop(flush = false)
        sched.runCurrent()
        assertEquals(1, source.closes)
        assertEquals(ContributionState.Idle, state)
        noLeakedCoroutines()
        advance(60_000)
        assertEquals(1, source.cues.size)
        assertEquals(1, source.closes)
    }

    @Test
    fun `a session that refuses to start closes the source`() = scenario {
        start(lookup = LookupOutcome.Exact)
        assertEquals(ContributionState.Finished(FinishReason.HaveExact), state)
        assertEquals(1, source.closes)
        start(fp = "nothex")
        assertEquals(ContributionState.Stopped(StopReason.Error), state)
    }

    @Test
    fun `replacing the session closes the previous source`() = scenario {
        start()
        advance(500)
        val second = FtCueSource()
        start(cueFrames = second)
        advance(500)
        assertEquals(1, source.closes)
        assertEquals(0, second.closes)
        contributor.stop(flush = false)
        sched.runCurrent()
        assertEquals(1, second.closes)
        noLeakedCoroutines()
    }

    @Test
    fun `the 0_5 two-argument Capturing still builds and means WhileWatching`() {
        val s = ContributionState.Capturing(3, 9)
        assertEquals(ContributionMode.WhileWatching, s.mode)
        assertFalse(s.fellBack)
    }
}
