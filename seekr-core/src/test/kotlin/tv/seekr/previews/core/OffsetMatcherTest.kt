package tv.seekr.previews.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tv.seekr.previews.core.internal.DHash
import tv.seekr.previews.core.internal.HashFetch
import tv.seekr.previews.core.internal.HashSource
import tv.seekr.previews.core.internal.VttCue

/**
 * End-to-end tests for offset matching: real frames, the real reduction, the real consensus.
 *
 * The hash list is built by hashing synthetic tiles with the shipped [DHash], so these
 * exercise the same path production does, minus the network. What they cannot prove is that
 * our reduction matches *the producer's* — that is what `DHashFixtureTest` is for, and the
 * two together are what make a passing suite meaningful.
 */
class OffsetMatcherTest {

    private val cueCount = 40
    private val intervalMs = 10_000L
    private val tile = SeekrTile(sheetUrl = "sheet-0", x = 0, y = 0, w = 320, h = 180)

    /** A uniform 10s grid, the shape the generator produces. */
    private fun cues(): List<VttCue> = (0 until cueCount).map {
        VttCue(startMs = it * intervalMs, endMs = (it + 1) * intervalMs, tile = tile)
    }

    private fun hashes(): LongArray = HASHES

    private fun pack(values: LongArray): ByteArray {
        val out = ByteArray(values.size * 8)
        var at = 0
        for (v in values) {
            for (shift in 56 downTo 0 step 8) out[at++] = (v ushr shift).toByte()
        }
        return out
    }

    private fun track(
        hashes: LongArray = hashes(),
        cues: List<VttCue> = cues(),
        algo: String = DHash.ALGO,
        declaredCount: Int = hashes.size,
        url: String = "https://sprites.seekr.tv/1/2/hashes.bin?sig=x",
        fetch: suspend (String) -> HashFetch = { HashFetch.Bytes(pack(hashes)) },
    ) = PreviewTrack(
        cues = cues,
        hashes = HashSource(
            hashesUrl = url,
            hashAlgo = algo,
            declaredCount = declaredCount,
            expectedCount = cues.size,
            fetcher = { fetch(it) },
        ),
    )

    /**
     * A burst as a player would capture one: [OffsetMatcher.RECOMMENDED_FRAMES_PER_BURST]
     * frames evenly across a single cue interval, decoded from a release running
     * [trueOffsetMs] behind the sprite source.
     *
     * The frames land wherever the spacing puts them, which is the point — only one of them
     * is near a cue, and the matcher has to work that out for itself.
     */
    private fun burst(basePlaybackMs: Long, trueOffsetMs: Long): Sample {
        val step = intervalMs / OffsetMatcher.RECOMMENDED_FRAMES_PER_BURST
        return Sample(
            (0 until OffsetMatcher.RECOMMENDED_FRAMES_PER_BURST).map { k ->
                val playbackMs = basePlaybackMs + k * step
                PreviewFrame(
                    image = SyntheticFrames.at(playbackMs + trueOffsetMs, intervalMs, cueCount),
                    playbackMs = playbackMs,
                )
            },
        )
    }

    // ── The hash list itself ──────────────────────────────────────────────────

    @Test
    fun `the synthetic hash list is not degenerate`() {
        // Guards the tests below: a list with repeated hashes would let a broken matcher
        // pass by accident, which is exactly why the producer quality-checks before storing.
        val list = hashes()
        assertEquals(cueCount, list.distinct().size, "every synthetic shot must hash distinctly")
    }

    // ── Matching ─────────────────────────────────────────────────────────────

    @Test
    fun `exact tiles resolve to a zero offset`() = runBlocking {
        val track = track()
        val samples = (0..4).map { n ->
            val index = 3 + n * 6
            Sample(
                PreviewFrame(SyntheticFrames.tile(index), playbackMs = index * intervalMs),
            )
        }
        val match = assertNotNull(track.matchOffset(samples))
        assertEquals(0L, match.offsetMs)
        assertEquals(0, match.medianDistanceBits, "an unmodified tile must reduce identically")
    }

    @Test
    fun `a release missing head content yields a positive offset`() = runBlocking {
        // previewTime = playbackTime + offsetMs. The played release is missing 20s the sprite
        // source had, so the scene at 40s of playback is at 60s in the sprite timeline.
        val trueOffsetMs = 20_000L
        val track = track()
        val samples = listOf(60_000L, 111_000L, 172_000L, 203_000L, 254_000L)
            .map { burst(it, trueOffsetMs) }

        val match = assertNotNull(track.matchOffset(samples))
        assertTrue(
            abs(match.offsetMs - trueOffsetMs) <= intervalMs / 2,
            "expected ~$trueOffsetMs, got ${match.offsetMs}",
        )
        assertTrue(match.agreeingBursts >= OffsetMatcher.REQUIRED_AGREEMENT)
        assertTrue(match.confidence > 0f)
    }

    @Test
    fun `a release with extra head content yields a negative offset`() = runBlocking {
        val trueOffsetMs = -30_000L
        val track = track()
        val samples = listOf(60_000L, 91_000L, 152_000L, 183_000L, 214_000L)
            .map { burst(it, trueOffsetMs) }

        val match = assertNotNull(track.matchOffset(samples))
        assertTrue(
            abs(match.offsetMs - trueOffsetMs) <= intervalMs / 2,
            "expected ~$trueOffsetMs, got ${match.offsetMs}",
        )
    }

    @Test
    fun `matching survives frames that are not byte-identical to the tile`() = runBlocking {
        // Stands in for a different encode of the same moment: same picture, shifted levels.
        val track = track()
        val indices = listOf(4, 11, 18, 25, 32)
        val samples = indices.map { index ->
            Sample(PreviewFrame(SyntheticFrames.noisyTile(index, amplitude = 10), index * intervalMs))
        }
        val match = assertNotNull(track.matchOffset(samples))
        assertEquals(0L, match.offsetMs)
        assertTrue(
            match.medianDistanceBits > 0,
            "the noisy frames should not have reduced identically, or this proves nothing",
        )
    }

    @Test
    fun `only the best frame in a burst votes`() = runBlocking {
        // Three frames from moments the sprite source has no tile for, plus one real one.
        // If the wrong frames voted, three garbage estimates would outvote the good one.
        val track = track()
        val samples = listOf(6, 13, 20, 27, 34).map { index ->
            val cueMs = index * intervalMs
            Sample(
                PreviewFrame(SyntheticFrames.at(cueMs + 4_400, intervalMs, cueCount), cueMs + 4_400),
                PreviewFrame(SyntheticFrames.at(cueMs + 5_100, intervalMs, cueCount), cueMs + 5_100),
                PreviewFrame(SyntheticFrames.tile(index), cueMs),
                PreviewFrame(SyntheticFrames.at(cueMs + 5_600, intervalMs, cueCount), cueMs + 5_600),
            )
        }
        val match = assertNotNull(track.matchOffset(samples))
        assertEquals(0L, match.offsetMs)
        assertEquals(0, match.medianDistanceBits, "the exact frame should have won every burst")
    }

    // ── Declining ────────────────────────────────────────────────────────────

    @Test
    fun `an ambiguous hash list declines instead of guessing`() = runBlocking {
        // Every tile identical: every frame matches index 0, so each burst produces a
        // different estimate and none of them agree. This is the case a bucket vote cannot
        // decline — five estimates always collapse into two buckets and one always wins.
        val degenerate = LongArray(cueCount) { 0x0F0F0F0F0F0F0F0FL }
        val track = track(hashes = degenerate)
        val samples = listOf(0L, 100_000L, 200_000L, 300_000L, 380_000L).map { base ->
            Sample(PreviewFrame(SyntheticFrames.tile(1), base))
        }

        assertNull(track.matchOffset(samples))
    }

    @Test
    fun `two agreeing bursts are not enough`() = runBlocking {
        val track = track()
        val matcher = track.offsetMatcher()
        assertNull(matcher.offer(Sample(PreviewFrame(SyntheticFrames.tile(5), 5 * intervalMs))))
        assertNull(matcher.offer(Sample(PreviewFrame(SyntheticFrames.tile(9), 9 * intervalMs))))
        assertEquals(MatchStatus.NEED_MORE_BURSTS, matcher.status)
        assertEquals(2, matcher.burstsAccepted)
    }

    // ── Incremental feeding ──────────────────────────────────────────────────

    @Test
    fun `bursts fed one at a time reach consensus on the third`() = runBlocking {
        val track = track()
        val matcher = track.offsetMatcher()

        assertNull(matcher.offer(burst(60_000, 20_000)))
        assertEquals(MatchStatus.NEED_MORE_BURSTS, matcher.status)

        assertNull(matcher.offer(burst(121_000, 20_000)))
        assertEquals(MatchStatus.NEED_MORE_BURSTS, matcher.status)

        val match = assertNotNull(matcher.offer(burst(182_000, 20_000)))
        assertEquals(MatchStatus.MATCHED, matcher.status)
        assertEquals(match, matcher.match)
        assertEquals(3, match.agreeingBursts)
        assertTrue(abs(match.offsetMs - 20_000L) <= intervalMs / 2)
    }

    @Test
    fun `only the most recent bursts are weighed`() = runBlocking {
        val track = track()
        val matcher = track.offsetMatcher()
        // Six bursts, so the first falls out of the window; the window is what bounds the
        // memory a caller who never gets an answer would otherwise accumulate forever.
        repeat(6) { n -> matcher.offer(burst(60_000 + n * 31_000L, 20_000)) }
        val match = assertNotNull(matcher.match)
        assertTrue(
            match.burstsConsidered <= OffsetMatcher.WINDOW_SIZE,
            "considered ${match.burstsConsidered}, window is ${OffsetMatcher.WINDOW_SIZE}",
        )
        assertEquals(6, matcher.burstsAccepted)
    }

    @Test
    fun `reset clears the evidence but not the download`() = runBlocking {
        var fetches = 0
        val track = track(fetch = { fetches++; HashFetch.Bytes(pack(hashes())) })
        val matcher = track.offsetMatcher()
        repeat(3) { n -> matcher.offer(burst(60_000 + n * 61_000L, 20_000)) }
        assertEquals(MatchStatus.MATCHED, matcher.status)

        matcher.reset()
        assertEquals(MatchStatus.NEED_MORE_BURSTS, matcher.status)
        assertNull(matcher.match)
        assertEquals(0, matcher.burstsAccepted)

        matcher.offer(burst(60_000, 20_000))
        assertEquals(1, fetches, "the hash list must be downloaded once per track")
    }

    @Test
    fun `an empty burst does not count as evidence`() = runBlocking {
        val track = track()
        val matcher = track.offsetMatcher()
        assertNull(matcher.offer(Sample(emptyList())))
        assertEquals(0, matcher.burstsAccepted)
        assertEquals(MatchStatus.NEED_MORE_BURSTS, matcher.status)
    }

    @Test
    fun `black frames never vote`() = runBlocking {
        // A frame grab that comes back black (a surface that was not drawn yet) hashes to
        // all zeros, exactly like a flat sprite tile, so without the guard it would "match".
        val black = PixelSource.ofArgb(256, 144, IntArray(256 * 144) { 0xFF000000.toInt() })
        val matcher = track().offsetMatcher()
        repeat(5) { k ->
            assertNull(matcher.offer(Sample((0 until 4).map { PreviewFrame(black, (60 + k * 45) * 1000L + it * 2500L) })))
        }
        assertEquals(0, matcher.burstsAccepted)
        assertEquals(MatchStatus.NEED_MORE_BURSTS, matcher.status)
    }

    @Test
    fun `a frame that fails to read is dropped, not thrown at the caller`() = runBlocking {
        // An Android Bitmap recycled by the player mid-match throws from getPixels. Matching
        // is a background nicety; surfacing it as a crash in the coroutine that drives
        // playback would be a catastrophic trade.
        val exploding = object : PixelSource {
            override val width = 320
            override val height = 180
            override fun readRow(y: Int, into: IntArray) = error("bitmap recycled")
        }
        val track = track()
        val samples = listOf(4, 11, 18, 25, 32).map { index ->
            Sample(
                PreviewFrame(exploding, index * intervalMs),
                PreviewFrame(SyntheticFrames.tile(index), index * intervalMs),
            )
        }
        val match = assertNotNull(track.matchOffset(samples))
        assertEquals(0L, match.offsetMs, "the readable frame in each burst should still win")
    }

    @Test
    fun `a match is withdrawn when the evidence stops supporting it`() = runBlocking {
        // Bursts roll out of the window over a long session, so a match made early can stop
        // being supported. A caller polling `match` instead of reading the return value must
        // not be handed an offset the matcher no longer stands behind.
        val track = track()
        val matcher = track.offsetMatcher()
        listOf(60_000L, 121_000L, 182_000L).forEach { matcher.offer(burst(it, 20_000)) }
        assertEquals(MatchStatus.MATCHED, matcher.status)
        assertNotNull(matcher.match)

        // Three mutually disagreeing bursts, pushing the agreeing ones out of the window.
        listOf(100_000L, 200_000L, 300_000L).forEach { matcher.offer(burst(60_000, it)) }

        assertEquals(MatchStatus.NO_CONSENSUS, matcher.status)
        assertNull(matcher.match, "a withdrawn match must not linger")
    }

    // ── Giving up ────────────────────────────────────────────────────────────

    @Test
    fun `a version with no hashes reports why and stops`() = runBlocking {
        var fetches = 0
        val track = track(url = "", fetch = { fetches++; HashFetch.Transient })
        val matcher = track.offsetMatcher()

        assertNull(matcher.offer(burst(60_000, 20_000)))
        assertEquals(MatchStatus.NO_HASHES_PUBLISHED, matcher.status)
        assertTrue(matcher.isTerminal, "the caller must be told to stop capturing frames")

        matcher.offer(burst(120_000, 20_000))
        assertEquals(0, fetches)
    }

    @Test
    fun `a hash list from a different generation declines rather than mis-indexing`() = runBlocking {
        val track = track(declaredCount = cueCount - 2)
        val matcher = track.offsetMatcher()
        assertNull(matcher.offer(burst(60_000, 20_000)))
        assertEquals(MatchStatus.HASH_COUNT_MISMATCH, matcher.status)
        assertTrue(matcher.isTerminal)
    }

    @Test
    fun `an unknown reduction declines`() = runBlocking {
        val track = track(algo = "dhash16")
        val matcher = track.offsetMatcher()
        assertNull(matcher.offer(burst(60_000, 20_000)))
        assertEquals(MatchStatus.UNSUPPORTED_ALGO, matcher.status)
        assertTrue(matcher.isTerminal)
    }

    @Test
    fun `a failed download is retried before it is given up on`() = runBlocking {
        var fetches = 0
        val track = track(fetch = { fetches++; HashFetch.Transient })
        val matcher = track.offsetMatcher()

        assertNull(matcher.offer(burst(60_000, 20_000)))
        assertEquals(MatchStatus.HASH_FETCH_FAILED, matcher.status)
        assertFalse(matcher.isTerminal, "one blip must not disable matching for the session")

        matcher.offer(burst(120_000, 20_000))
        matcher.offer(burst(180_000, 20_000))
        assertTrue(matcher.isTerminal, "but it must not retry forever either")
        assertEquals(3, fetches)
    }

    @Test
    fun `an empty track never tries to match`() = runBlocking {
        val track = PreviewTrack(emptyList())
        val matcher = track.offsetMatcher()
        assertNull(matcher.offer(Sample(PreviewFrame(SyntheticFrames.tile(0), 0))))
        assertEquals(MatchStatus.EMPTY_TRACK, matcher.status)
        assertTrue(matcher.isTerminal)
        assertNull(track.matchOffset(listOf(Sample(PreviewFrame(SyntheticFrames.tile(0), 0)))))
    }

    companion object {
        // Hashing 40 frames is cheap but not free, and every test wants the same list.
        private val HASHES: LongArray by lazy {
            LongArray(40) { DHash.hash(SyntheticFrames.tile(it), 320, 180) }
        }
    }
}
