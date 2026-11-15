package tv.seekr.previews.android

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tv.seekr.previews.android.internal.Backoff
import tv.seekr.previews.android.internal.FileMeta
import tv.seekr.previews.android.internal.Gate
import tv.seekr.previews.android.internal.NeedsStep
import tv.seekr.previews.android.internal.StatsRecorder
import tv.seekr.previews.android.internal.TileCache
import tv.seekr.previews.android.internal.UploadStep
import tv.seekr.previews.android.internal.Uploader
import tv.seekr.previews.core.contrib.ContributionError
import tv.seekr.previews.core.contrib.NeedsReason
import tv.seekr.previews.core.contrib.NeedsResult
import tv.seekr.previews.core.contrib.NvmtWriter
import tv.seekr.previews.core.contrib.SubmitResult

/** Network gating and every [tv.seekr.previews.core.contrib.ErrorPolicy] branch of the uploader, against a scripted API. */
class UploaderTest {
    private val config = ContributorConfig()
    private val meta = FileMeta(MOVIE, 600_000) // 60 slots
    private lateinit var api: FakeApi
    private lateinit var cache: TileCache
    private lateinit var stats: StatsRecorder
    private lateinit var uploader: Uploader
    private var now = 1_000_000L
    private var networkOk = true

    @BeforeTest
    fun setUp() {
        now = 1_000_000L
        networkOk = true
        api = FakeApi().apply { needsAnswer = { NeedsResult.Success(needs(60, needed = 0..59)) } }
        cache = TileCache(tempDir(), 20L * 1024 * 1024, 30L * 24 * 3600 * 1000, { now })
        stats = StatsRecorder(FakeSharedPreferences())
        uploader = Uploader(api, cache, stats, { networkOk }, Backoff(config, { now }), config, { now }, 30_000)
    }

    private fun fill(vararg cues: Int, fp: String = FP) = cues.forEach { cache.put(fp, meta, it, it * 10_000L, fakeJpeg()) }

    private fun upload(fp: String = FP) = runBlocking { uploader.upload(fp) }

    private fun blocked(step: UploadStep): Gate = assertIs<UploadStep.Blocked>(step).gate

    private fun failWith(error: ContributionError) {
        api.submitAnswer = { SubmitResult.Failure(error) }
    }

    // ---- success path ----

    @Test
    fun `refreshes needs, sends the cached cues as one segment, clears them and counts stats`() {
        fill(3, 4, 9)
        val step = assertIs<UploadStep.Uploaded>(upload())
        assertEquals(3, step.tiles)
        assertEquals(1, api.needsCalls.size, "needs is asked right before the upload")
        val sent = api.submits.single()
        assertEquals(FP, sent.fp)
        assertEquals(MOVIE, sent.content)
        assertEquals(600_000L, sent.durationMs)
        assertEquals(listOf(3, 4, 9), sent.cues)
        assertEquals(listOf(3L * 10_000, 4L * 10_000, 9L * 10_000), nvmtTiles(sent.nvmt).map { it.second })
        assertEquals(60, nvmtSlots(sent.nvmt), "n_slots from the job")
        assertTrue(cache.tiles(FP).isEmpty())
        assertEquals(3L, stats.stats.value.tilesUploaded)
        assertEquals(1, stats.stats.value.titlesUploaded)
        assertEquals(now, stats.stats.value.lastUploadAtMs)
    }

    @Test
    fun `only cues still in needed or overlap are sent, the rest are dropped from the cache`() {
        api.needsAnswer = { NeedsResult.Success(needs(60, needed = listOf(2, 3), overlap = listOf(7))) }
        fill(2, 3, 5, 7, 8)
        assertIs<UploadStep.Uploaded>(upload())
        assertEquals(listOf(2, 3, 7), api.submits.single().cues)
        assertTrue(cache.tiles(FP).isEmpty(), "5 and 8 are no longer wanted")
    }

    @Test
    fun `cues beyond n_slots are not sent`() {
        api.needsAnswer = { NeedsResult.Success(needs(5, needed = 0..4)) }
        fill(1, 7)
        assertIs<UploadStep.Uploaded>(upload())
        assertEquals(listOf(1), api.submits.single().cues)
    }

    @Test
    fun `nothing wanted means no submit`() {
        api.needsAnswer = { NeedsResult.Success(needs(60, needed = listOf(50))) }
        fill(1, 2)
        assertIs<UploadStep.Nothing>(upload())
        assertTrue(api.submits.isEmpty())
    }

    @Test
    fun `a duplicate is accepted but not counted again`() {
        api.submitAnswer = { accepted(duplicate = true) }
        fill(1)
        assertIs<UploadStep.Uploaded>(upload())
        assertEquals(0L, stats.stats.value.tilesUploaded)
        assertTrue(cache.tiles(FP).isEmpty())
    }

    @Test
    fun `a large buffer is split into segments under 16 MB`() {
        api.needsAnswer = { NeedsResult.Success(needs(120, needed = 0..119)) }
        for (cue in 0 until 100) cache.put(FP, meta, cue, cue * 10_000L, fakeJpeg(190_000)) // 19 MB
        assertIs<UploadStep.Uploaded>(upload())
        assertEquals(2, api.submits.size)
        assertTrue(api.submits.all { it.nvmt.size <= NvmtWriter.MAX_PACKAGE_BYTES })
        assertEquals((0 until 100).toList(), api.submits.flatMap { it.cues })
        assertEquals(1, api.needsCalls.size)
    }

    @Test
    fun `nothing cached is a no-op without network`() {
        assertIs<UploadStep.Nothing>(upload())
        assertTrue(api.needsCalls.isEmpty())
    }

    // ---- network gating ----

    @Test
    fun `on a metered network nothing touches the network and tiles stay cached`() {
        fill(1, 2)
        networkOk = false
        val gate = assertIs<Gate.Wait>(blocked(upload()))
        assertEquals(WaitReason.WaitingForNetwork, gate.reason)
        assertTrue(api.needsCalls.isEmpty() && api.submits.isEmpty())
        assertEquals(setOf(1, 2), cache.cues(FP))
        networkOk = true
        assertIs<UploadStep.Uploaded>(upload())
    }

    @Test
    fun `needs is gated by the network too`() {
        networkOk = false
        val step = runBlocking { uploader.needs(FP, meta) }
        assertEquals(WaitReason.WaitingForNetwork, assertIs<Gate.Wait>(assertIs<NeedsStep.Blocked>(step).gate).reason)
        assertTrue(api.needsCalls.isEmpty())
    }

    // ---- policies ----

    @Test
    fun `RetryAfter with a Retry-After header waits exactly that long and makes no calls meanwhile`() {
        fill(1)
        failWith(ContributionError.RateLimited(retryAfterSec = 120))
        val gate = assertIs<Gate.Wait>(blocked(upload()))
        assertEquals(WaitReason.BackingOff, gate.reason)
        assertEquals(120_000L, gate.retryInMs)
        assertEquals(setOf(1), cache.cues(FP), "kept for the retry")
        val calls = api.needsCalls.size + api.submits.size
        now += 119_000
        assertEquals(WaitReason.BackingOff, assertIs<Gate.Wait>(blocked(upload())).reason)
        assertEquals(calls, api.needsCalls.size + api.submits.size)
        now += 1_001
        api.submitAnswer = { accepted() }
        assertIs<UploadStep.Uploaded>(upload())
    }

    @Test
    fun `RetryAfter without a header backs off exponentially from 1 minute and a success resets it`() {
        fill(1)
        failWith(ContributionError.Server(502))
        assertEquals(60_000L, assertIs<Gate.Wait>(blocked(upload())).retryInMs)
        now += 60_000
        assertEquals(120_000L, assertIs<Gate.Wait>(blocked(upload())).retryInMs)
        now += 120_000
        failWith(ContributionError.Network)
        assertEquals(240_000L, assertIs<Gate.Wait>(blocked(upload())).retryInMs)
        now += 240_000
        api.submitAnswer = { accepted() }
        assertIs<UploadStep.Uploaded>(upload())
        fill(2)
        failWith(ContributionError.ServerBusy(null))
        assertEquals(60_000L, assertIs<Gate.Wait>(blocked(upload())).retryInMs)
    }

    @Test
    fun `backoff reaches 6 hours and stays there`() {
        fill(1)
        failWith(ContributionError.Server(500))
        var last = 0L
        repeat(14) {
            last = assertIs<Gate.Wait>(blocked(upload())).retryInMs
            now += last
        }
        assertEquals(6L * 60 * 60 * 1000, last)
    }

    @Test
    fun `installation_too_new waits at least 10 minutes`() {
        fill(1)
        failWith(ContributionError.InstallationTooNew)
        assertEquals(10L * 60 * 1000, assertIs<Gate.Wait>(blocked(upload())).retryInMs)
    }

    @Test
    fun `a needs failure backs off like an upload failure`() {
        fill(1)
        api.needsAnswer = { NeedsResult.Failure(ContributionError.RateLimited(30)) }
        assertEquals(30_000L, assertIs<Gate.Wait>(blocked(upload())).retryInMs)
        assertTrue(api.submits.isEmpty())
    }

    @Test
    fun `408 is retried`() {
        fill(1)
        failWith(ContributionError.Rejected(408, "upload_too_slow", null))
        assertIs<Gate.Wait>(blocked(upload()))
        assertEquals(setOf(1), cache.cues(FP))
    }

    @Test
    fun `job_closed drops that files cache`() {
        fill(1, 2)
        fill(5, fp = FP2)
        failWith(ContributionError.JobClosed("closed"))
        val gate = assertIs<Gate.Finish>(blocked(upload()))
        assertEquals(FinishReason.JobClosed, gate.reason)
        assertTrue(gate.terminal)
        assertTrue(cache.tiles(FP).isEmpty())
        assertNull(cache.meta(FP))
        assertEquals(setOf(5), cache.cues(FP2), "other files are untouched")
    }

    @Test
    fun `422 stops that file for good and later attempts make no request`() {
        fill(1, 2)
        failWith(ContributionError.Rejected(422, "unprocessable", null))
        assertEquals(StopReason.FileRejected, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertTrue(cache.isStopped(FP))
        assertTrue(cache.tiles(FP).isEmpty())
        val calls = api.needsCalls.size + api.submits.size
        fill(3)
        assertEquals(StopReason.FileRejected, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertEquals(calls, api.needsCalls.size + api.submits.size)
        // other files still work
        api.submitAnswer = { accepted() }
        fill(1, fp = FP2)
        assertIs<UploadStep.Uploaded>(upload(FP2))
    }

    @Test
    fun `job_cap job_mismatch and a plain 400 stop the file`() {
        for (error in listOf(
            ContributionError.JobCap("tiles"),
            ContributionError.JobMismatch(61, 610_000),
            ContributionError.Rejected(400, "bad_format", null),
            ContributionError.UnsupportedContent,
        )) {
            val fp = if (error is ContributionError.JobCap) FP else FP2
            cache.drop(fp)
            fill(1, fp = fp)
            failWith(error)
            assertEquals(StopReason.FileRejected, assertIs<Gate.Stop>(blocked(upload(fp))).reason, "$error")
            assertTrue(cache.isStopped(fp))
            cache.drop(fp)
        }
    }

    @Test
    fun `403 stops everything until restart and keeps the tiles`() {
        fill(1, 2)
        fill(4, fp = FP2)
        failWith(ContributionError.NotAllowed)
        assertEquals(StopReason.NotAllowed, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertEquals(StopReason.NotAllowed, uploader.halted)
        assertEquals(setOf(1, 2), cache.cues(FP), "kept: a restart resumes them")
        val calls = api.needsCalls.size + api.submits.size
        assertEquals(StopReason.NotAllowed, assertIs<Gate.Stop>(blocked(upload(FP2))).reason)
        val needsStep = runBlocking { uploader.needs(FP2, meta) }
        assertIs<Gate.Stop>(assertIs<NeedsStep.Blocked>(needsStep).gate)
        assertEquals(calls, api.needsCalls.size + api.submits.size, "no request at all after a 403")
    }

    @Test
    fun `every 403 flavour halts, disabled with its own reason`() {
        for ((error, reason) in listOf(
            ContributionError.NotEnabled to StopReason.NotAllowed,
            ContributionError.Suspended to StopReason.NotAllowed,
            ContributionError.Disabled to StopReason.Disabled,
            ContributionError.Rejected(403, "forbidden", null) to StopReason.NotAllowed,
        )) {
            setUp()
            fill(1)
            failWith(error)
            assertEquals(reason, assertIs<Gate.Stop>(blocked(upload())).reason, "$error")
            assertEquals(reason, uploader.halted)
        }
    }

    @Test
    fun `needs 403 halts too`() {
        fill(1)
        api.needsAnswer = { NeedsResult.Failure(ContributionError.NotEnabled) }
        assertEquals(StopReason.NotAllowed, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertEquals(StopReason.NotAllowed, uploader.halted)
    }

    @Test
    fun `401 and signed out stop the session but not the process`() {
        fill(1)
        failWith(ContributionError.Unauthorized)
        assertEquals(StopReason.SignedOut, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertNull(uploader.halted)
        assertEquals(setOf(1), cache.cues(FP))
        api.needsAnswer = { NeedsResult.Failure(ContributionError.SignedOut) }
        assertEquals(StopReason.SignedOut, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertNull(uploader.halted)
    }

    @Test
    fun `not_observed keeps the tiles and asks to retry needs later`() {
        fill(1)
        api.needsAnswer = { NeedsResult.Failure(ContributionError.NotObserved) }
        assertEquals(Gate.NeedsLater, blocked(upload()))
        assertEquals(setOf(1), cache.cues(FP))
        api.needsAnswer = { NeedsResult.Success(needs(60, needed = 0..59)) }
        failWith(ContributionError.NotObserved)
        assertEquals(Gate.NeedsLater, blocked(upload()))
        assertEquals(setOf(1), cache.cues(FP))
        assertFalse(uploader.backoff.active, "not_observed uses its own 60 s spacing, not the backoff")
    }

    // ---- needs answers that end the file ----

    @Test
    fun `needs reasons that close the job drop the cache`() {
        for ((reason, finish) in listOf(
            NeedsReason.HaveExact to FinishReason.HaveExact,
            NeedsReason.JobClosed to FinishReason.JobClosed,
            NeedsReason.Ignored to FinishReason.Ignored,
            NeedsReason.BlockedTakedown to FinishReason.BlockedTakedown,
        )) {
            setUp()
            fill(1, 2)
            api.needsAnswer = { NeedsResult.Success(needs(60, emptyList(), generate = false, reason = reason)) }
            val gate = assertIs<Gate.Finish>(blocked(upload()))
            assertEquals(finish, gate.reason)
            assertTrue(gate.terminal)
            assertTrue(cache.tiles(FP).isEmpty(), "$reason")
        }
    }

    @Test
    fun `needs Ok with nothing needed is not terminal and keeps the cache`() {
        fill(1)
        api.needsAnswer = { NeedsResult.Success(needs(60, emptyList(), generate = false, reason = NeedsReason.Ok)) }
        val gate = assertIs<Gate.Finish>(blocked(upload()))
        assertEquals(FinishReason.NothingNeeded, gate.reason)
        assertFalse(gate.terminal)
        assertEquals(setOf(1), cache.cues(FP))
        assertTrue(api.submits.isEmpty())
    }

    @Test
    fun `needs job_cap stops the file, not_observed asks to retry`() {
        fill(1)
        api.needsAnswer = { NeedsResult.Success(needs(60, emptyList(), generate = false, reason = NeedsReason.JobCap)) }
        assertEquals(StopReason.FileRejected, assertIs<Gate.Stop>(blocked(upload())).reason)
        assertTrue(cache.isStopped(FP))
        fill(1, fp = FP2)
        api.needsAnswer = { NeedsResult.Success(needs(60, emptyList(), generate = false, reason = NeedsReason.NotObserved)) }
        assertEquals(Gate.NeedsLater, blocked(upload(FP2)))
    }
}
