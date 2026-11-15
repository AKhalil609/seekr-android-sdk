package tv.seekr.previews.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import tv.seekr.previews.android.internal.FullCaptureScheduler
import tv.seekr.previews.android.internal.FullCaptureTiming

private class SchedPlayer(private val now: () -> Long) : PlaybackProbe {
    /** When set, [PlaybackSnapshot.msSinceLastSeek] follows the virtual clock. */
    var seekAt: Long? = null
    @Volatile
    var snap = PlaybackSnapshot(
        positionMs = 0, isPlaying = false, speed = 1f, msSinceLastSeek = Long.MAX_VALUE, msSinceLastStall = Long.MAX_VALUE,
        bufferedAheadMs = 120_000, isHdr = false, isSecure = false, droppedFramesRecent = 0, thermalThrottled = false,
    )
    override fun snapshot() = seekAt?.let { snap.copy(msSinceLastSeek = now() - it) } ?: snap
    fun update(f: PlaybackSnapshot.() -> PlaybackSnapshot) { snap = snap.f() }
}

private class ScriptedSource(private val now: () -> Long) : CueFrameSource {
    val calls = mutableListOf<Pair<Int, Long>>() // cue, virtual time
    var inFlight = 0
    var maxInFlight = 0
    var closes = 0
    var cancelledCalls = 0
    var handler: suspend (Int) -> CueFrameResult = { cue -> frame(cue) }

    fun frame(cue: Int, offset: Long = 0) = CueFrameResult.Frame(CapturedVideoFrame(16, 9, IntArray(144), cue * 10_000L + offset), cue * 10_000L + offset)

    override suspend fun frameAt(cueIndex: Int, targetMs: Long): CueFrameResult {
        calls += cueIndex to now()
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            return handler(cueIndex)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledCalls++
            throw e
        } finally {
            inFlight--
        }
    }

    override fun close() { closes++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FullCaptureSchedulerTest {
    private class Rig(val test: TestScope, remainingCues: Collection<Int>, durationMs: Long = 1_000_000L, timing: FullCaptureTiming = FullCaptureTiming()) {
        val sched = test.testScheduler
        val clock = { 5_000_000L + sched.currentTime }
        val player = SchedPlayer { sched.currentTime }
        val source = ScriptedSource { sched.currentTime }
        val remaining: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().also { it += remainingCues }
        val stored = mutableListOf<Triple<Int, Long, Long>>() // cue, pts, time
        var deviceThrottled = false
        val scheduler = FullCaptureScheduler(source, player, clock, durationMs, timing) { deviceThrottled }
        val scope = CoroutineScope(StandardTestDispatcher(sched))

        init {
            scheduler.ledger = FullCaptureScheduler.Ledger(remaining) { cue, pts, _ ->
                remaining.remove(cue)
                stored += Triple(cue, pts, sched.currentTime)
                true
            }
        }

        fun start() = scheduler.start(scope, StandardTestDispatcher(sched))
        fun advance(ms: Long) { sched.advanceTimeBy(ms); sched.runCurrent() }
        fun callCount() = source.calls.size
    }

    private fun rig(cues: Collection<Int>, durationMs: Long = 1_000_000L, timing: FullCaptureTiming = FullCaptureTiming(), block: suspend Rig.() -> Unit) = runTest {
        val r = Rig(this, cues, durationMs, timing)
        try {
            r.start()
            r.block()
        } finally {
            r.scheduler.cancel()
            r.sched.runCurrent()
        }
    }

    @Test
    fun `orders from just ahead of the playhead to the end, then wraps from 0`() = rig(listOf(0, 2, 3, 4, 5, 8, 9)) {
        player.update { copy(positionMs = 35_000) } // inside cue 3: the next one is 4
        advance(20_000)
        assertEquals(listOf(4, 5, 8, 9, 0, 2, 3), source.calls.map { it.first })
        assertEquals(7, stored.size)
    }

    @Test
    fun `a cue the watch path captured while the frame was decoding is not stored again`() = rig(listOf(5, 6)) {
        source.handler = { cue ->
            delay(100)
            if (cue == 5) remaining.remove(5) // the while-watching path got it meanwhile
            source.frame(cue)
        }
        advance(5_000)
        assertEquals(listOf(5, 6), source.calls.map { it.first })
        assertEquals(listOf(6), stored.map { it.first })
    }

    @Test
    fun `cues not in the ledger are never asked for`() = rig(listOf(7)) {
        advance(10_000)
        assertEquals(listOf(7), source.calls.map { it.first })
        assertTrue(remaining.isEmpty())
        advance(10_000)
        assertEquals(1, callCount(), "nothing left, nothing decoded")
    }

    @Test
    fun `paused paces one frame per 400 ms`() = rig((1..50).toList()) {
        advance(4_000)
        val times = source.calls.map { it.second }
        assertTrue(times.size in 9..11, "got ${times.size}")
        times.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 400, "gap ${b - a}") }
    }

    @Test
    fun `playing paces one frame per 3 s`() = rig((1..50).toList()) {
        player.update { copy(isPlaying = true) }
        advance(15_000)
        val times = source.calls.map { it.second }
        assertTrue(times.size in 4..6, "got ${times.size}")
        times.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 3_000, "gap ${b - a}") }
    }

    @Test
    fun `the gap counts from the end of a slow call`() = rig((1..50).toList()) {
        source.handler = { cue -> delay(1_000); source.frame(cue) }
        advance(4_000)
        source.calls.map { it.second }.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 1_400, "gap ${b - a}") }
        assertEquals(1, source.maxInFlight)
    }

    private fun assertHoldsThenResumes(name: String, hold: PlaybackSnapshot.() -> PlaybackSnapshot, release: PlaybackSnapshot.() -> PlaybackSnapshot, expected: WaitReason?) = rig((1..200).toList()) {
        advance(1_000)
        val before = callCount()
        assertTrue(before > 0, name)
        player.update(hold)
        advance(500) // let an in-progress interval finish
        val held = callCount()
        advance(20_000)
        assertEquals(held, callCount(), "$name: no extractor work while held")
        assertEquals(expected, scheduler.pauseReason, name)
        assertFalse(scheduler.fellBack, name)
        player.update(release)
        advance(2_000)
        assertTrue(callCount() > held, "$name: resumed by itself")
        assertNull(scheduler.pauseReason)
    }

    @Test
    fun `holds while stalled and resumes`() =
        assertHoldsThenResumes("stall", { copy(msSinceLastStall = 0) }, { copy(msSinceLastStall = Long.MAX_VALUE) }, WaitReason.Buffering)

    @Test
    fun `holds for 5 s after a seek and resumes`() = rig((1..200).toList()) {
        advance(1_000)
        player.seekAt = sched.currentTime
        advance(500) // an interval that was already running may still finish
        val base = callCount()
        advance(4_400)
        assertEquals(base, callCount(), "quiet until 5 s after the seek")
        assertEquals(WaitReason.Seeking, scheduler.pauseReason)
        advance(1_500)
        assertTrue(callCount() > base, "resumed by itself")
        assertNull(scheduler.pauseReason)
    }

    @Test
    fun `holds while buffered ahead is under 30 s and resumes`() =
        assertHoldsThenResumes("buffer", { copy(bufferedAheadMs = 29_999) }, { copy(bufferedAheadMs = 30_000) }, WaitReason.Buffering)

    @Test
    fun `holds on thermal throttling and resumes`() =
        assertHoldsThenResumes("thermal", { copy(thermalThrottled = true) }, { copy(thermalThrottled = false) }, null)

    @Test
    fun `holds for HDR and secure surfaces`() =
        assertHoldsThenResumes("hdr", { copy(isHdr = true) }, { copy(isHdr = false) }, null)

    @Test
    fun `the end of the file does not count as a thin buffer`() = rig((1..200).toList(), durationMs = 600_000) {
        player.update { copy(positionMs = 590_000, bufferedAheadMs = 10_000) }
        advance(2_000)
        assertTrue(callCount() > 0, "10 s left and all of it buffered")
        val before = callCount()
        player.update { copy(positionMs = 100_000, bufferedAheadMs = 10_000) }
        advance(500)
        val held = callCount()
        advance(5_000)
        assertEquals(held, callCount(), "10 s ahead in the middle of the file is a thin buffer")
        assertTrue(held - before <= 1)
    }

    @Test
    fun `dropped frames that keep rising pause the worker until they settle`() = rig((1..200).toList()) {
        advance(1_000)
        assertTrue(callCount() > 0)
        player.update { copy(droppedFramesRecent = 5) }
        advance(450) // noticed on the next look at the player
        val held = callCount()
        assertEquals(WaitReason.Buffering, scheduler.pauseReason)
        repeat(8) {
            player.update { copy(droppedFramesRecent = droppedFramesRecent + 1) } // still dropping
            advance(500)
        }
        assertEquals(held, callCount(), "no extractor work while the count rises")
        advance(2_000) // stable at 13: not a rise any more
        assertTrue(callCount() > held)
        assertFalse(scheduler.fellBack, "a rise we did not cause is not a violation")
    }

    @Test
    fun `never more than one call in flight and a call over 8 s is a timeout error`() = rig(listOf(1, 2, 3, 4)) {
        source.handler = { awaitCancellation() }
        advance(7_900)
        assertEquals(1, callCount())
        assertEquals(1, source.maxInFlight)
        advance(200) // 8 s -> cancelled, counted as an error
        assertEquals(1, source.cancelledCalls)
        assertEquals(0, source.inFlight)
        assertFalse(scheduler.fellBack)
        advance(30_000)
        assertEquals(3, callCount(), "third consecutive timeout ends full capture")
        assertTrue(scheduler.fellBack)
        assertEquals(1, source.maxInFlight)
        assertEquals(1, source.closes)
        assertFalse(scheduler.running)
    }

    @Test
    fun `frames within 3 s of the cue are stored, further ones are not`() = rig(listOf(1, 2, 3, 4, 5)) {
        val offsets = mapOf(1 to 2_900L, 2 to -2_900L, 3 to 3_100L, 4 to 10_500L, 5 to 0L)
        source.handler = { cue -> source.frame(cue, offsets.getValue(cue)) }
        advance(10_000)
        assertEquals(listOf(1, 2, 5), stored.map { it.first })
        assertEquals(listOf(12_900L, 17_100L, 50_000L), stored.map { it.second })
        assertEquals(5, callCount(), "a dropped cue is not retried")
        assertFalse(scheduler.fellBack, "drift is not an error")
    }

    @Test
    fun `an unknown pts is dropped`() = rig(listOf(1)) {
        source.handler = { cue -> CueFrameResult.Frame(CapturedVideoFrame(16, 9, IntArray(144), -1), -1) }
        advance(3_000)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `Skip is not an error and the cue is not asked again`() = rig(listOf(1, 2, 3, 4, 5, 6)) {
        source.handler = { cue -> if (cue <= 4) CueFrameResult.Skip else source.frame(cue) }
        advance(10_000)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), source.calls.map { it.first })
        assertEquals(listOf(5, 6), stored.map { it.first })
        assertFalse(scheduler.fellBack)
    }

    @Test
    fun `Fatal falls back at once and closes the source`() = rig(listOf(1, 2, 3)) {
        source.handler = { CueFrameResult.Fatal("no decoder") }
        advance(5_000)
        assertEquals(1, callCount())
        assertTrue(scheduler.fellBack)
        assertEquals("no decoder", scheduler.fallbackReason)
        assertEquals(1, source.closes)
        assertFalse(scheduler.running)
    }

    @Test
    fun `three consecutive errors fall back, a success in between resets the count`() = rig((1..20).toList()) {
        val fellBackAtCall = mutableListOf<Boolean>()
        source.handler = { cue ->
            fellBackAtCall += scheduler.fellBack
            // error, error, success, error, error, error
            if (fellBackAtCall.size == 3) source.frame(cue) else throw IllegalStateException("decode failed")
        }
        advance(10_000)
        assertEquals(6, callCount(), "two errors + success did not count as three in a row")
        assertTrue(fellBackAtCall.none { it })
        assertTrue(scheduler.fellBack)
        assertEquals(1, source.closes)
    }

    @Test
    fun `an Error from the decoder never escapes`() = rig(listOf(1, 2, 3)) {
        source.handler = { throw OutOfMemoryError("decoder") }
        advance(5_000)
        assertTrue(scheduler.fellBack)
        assertEquals(3, callCount())
    }

    @Test
    fun `two pacing violations fall back, one does not`() = rig((1..50).toList()) {
        player.update { copy(isPlaying = true) }
        var violations = 0
        source.handler = { cue ->
            if (violations < 2) {
                violations++
                player.update { copy(droppedFramesRecent = droppedFramesRecent + 3) } // frames dropped while our call ran
            }
            source.frame(cue)
        }
        advance(100)
        assertEquals(1, callCount())
        assertFalse(scheduler.fellBack, "one violation is tolerated")
        advance(20_000)
        assertTrue(scheduler.fellBack)
        assertEquals(2, callCount(), "stopped right after the second violation")
        assertEquals(1, source.closes)
        assertEquals(2, stored.size, "the frames themselves were fine")
    }

    @Test
    fun `dropped frames while paused are not attributed to the extractor`() = rig((1..50).toList()) {
        source.handler = { cue ->
            player.update { copy(droppedFramesRecent = droppedFramesRecent + 3) }
            source.frame(cue)
        }
        advance(10_000)
        assertFalse(scheduler.fellBack)
    }

    @Test
    fun `cancelling is prompt, closes once and leaves no coroutine`() = rig((1..20).toList()) {
        source.handler = { awaitCancellation() }
        advance(1_000)
        assertEquals(1, source.inFlight)
        scheduler.cancel()
        sched.runCurrent()
        assertEquals(0, source.inFlight, "the in-flight call was cancelled")
        assertEquals(1, source.closes)
        assertFalse(scheduler.running)
        advance(60_000)
        assertEquals(1, callCount())
        assertEquals(1, source.closes)
    }

    @Test
    fun `cancelled before it ever ran still closes the source`() = runTest {
        val r = Rig(this, listOf(1))
        r.start()
        r.scheduler.cancel()
        r.sched.runCurrent()
        assertEquals(1, r.source.closes)
        assertEquals(0, r.callCount())
    }

    @Test
    fun `no ledger means no work`() = runTest {
        val r = Rig(this, listOf(1, 2))
        try {
            r.scheduler.ledger = null
            r.start()
            r.advance(10_000)
            assertEquals(0, r.callCount())
            r.scheduler.ledger = FullCaptureScheduler.Ledger(r.remaining) { cue, _, _ -> r.remaining.remove(cue) }
            r.advance(3_000)
            assertTrue(r.callCount() > 0)
        } finally {
            r.scheduler.cancel()
            r.sched.runCurrent()
        }
    }

    @Test
    fun `a throwing probe or store does not crash the worker`() = rig(listOf(1, 2, 3, 4, 5, 6)) {
        scheduler.ledger = FullCaptureScheduler.Ledger(remaining) { _, _, _ -> throw IllegalStateException("disk full") }
        advance(10_000)
        assertTrue(scheduler.fellBack, "repeated store failures count as errors")
        assertEquals(1, source.closes)
    }
}
