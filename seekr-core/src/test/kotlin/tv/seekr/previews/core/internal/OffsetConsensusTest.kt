package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consensus rules, on synthetic estimates.
 *
 * These pin the two decisions that make the feature safe, both of which were settled by
 * measurement in `Zoetrope/scripts/verify_sampling.py` rather than by taste:
 * agreement is a **window**, and three bursts must reach it.
 */
class OffsetConsensusTest {

    private fun vote(offsetMs: Long, playbackMs: Long = 0L, distance: Int = 2, runnerUp: Int = 20) =
        OffsetConsensus.Vote(offsetMs, playbackMs, distance, runnerUp)

    @Test
    fun `tolerance window is half a cue interval plus the snap jitter`() {
        assertEquals(8_000L, OffsetConsensus.toleranceMs(10_000L))
        assertEquals(5_500L, OffsetConsensus.toleranceMs(5_000L))
    }

    @Test
    fun `search returns the closest hash with the runner-up distance`() {
        val hashes = longArrayOf(0b0000L, 0b0011L, 0b1111L)
        val found = assertNotNull(OffsetConsensus.search(hashes, 0b0001L))
        assertEquals(0, found.index)
        assertEquals(1, found.distance)
        assertEquals(1, found.runnerUp, "0b0011 differs from 0b0001 by one bit too")
    }

    @Test
    fun `search on an empty list finds nothing`() {
        assertNull(OffsetConsensus.search(LongArray(0), 1L))
    }

    @Test
    fun `three estimates inside the window agree even though none are equal`() {
        // The whole point of window clustering: real off-grid estimates are near-misses of
        // each other, never repeats. Exact-equality voting scored 7.6% against 24.4% here.
        val cluster = assertNotNull(
            OffsetConsensus.cluster(
                listOf(vote(19_100), vote(22_400), vote(24_900)),
                toleranceMs = 8_000L,
            ),
        )
        assertEquals(3, cluster.members.size)
        assertEquals(22_400L, cluster.offsetMs, "the median of the agreeing group")
        assertEquals(5_800L, cluster.spreadMs)
    }

    @Test
    fun `two agreeing estimates are not enough`() {
        // 2-of-5 mis-syncs 19% on a degenerate title; 3-of-5 mis-syncs 0%.
        assertNull(
            OffsetConsensus.cluster(
                listOf(vote(20_000), vote(21_000), vote(90_000), vote(-40_000)),
                toleranceMs = 8_000L,
            ),
        )
    }

    @Test
    fun `outliers are excluded rather than dragging the answer`() {
        val cluster = assertNotNull(
            OffsetConsensus.cluster(
                listOf(vote(20_000), vote(21_000), vote(22_000), vote(500_000), vote(-90_000)),
                toleranceMs = 8_000L,
            ),
        )
        assertEquals(3, cluster.members.size)
        assertEquals(21_000L, cluster.offsetMs)
        assertEquals(5, cluster.considered)
    }

    @Test
    fun `estimates exactly on the window boundary agree`() {
        val cluster = assertNotNull(
            OffsetConsensus.cluster(
                listOf(vote(0), vote(8_000), vote(4_000)),
                toleranceMs = 8_000L,
            ),
        )
        assertEquals(3, cluster.members.size)
        assertEquals(4_000L, cluster.offsetMs)
    }

    @Test
    fun `estimates one millisecond outside the window do not`() {
        assertNull(
            OffsetConsensus.cluster(
                listOf(vote(0), vote(8_001), vote(16_002)),
                toleranceMs = 8_000L,
            ),
        )
    }

    @Test
    fun `confidence rises with agreement, closeness, margin and tightness`() {
        val weak = assertNotNull(
            OffsetConsensus.cluster(
                listOf(
                    vote(20_000, distance = 14, runnerUp = 15),
                    vote(27_000, distance = 14, runnerUp = 15),
                    vote(13_500, distance = 14, runnerUp = 15),
                ),
                toleranceMs = 8_000L,
            ),
        )
        val strong = assertNotNull(
            OffsetConsensus.cluster(
                listOf(
                    vote(20_000, distance = 0, runnerUp = 24),
                    vote(20_100, distance = 0, runnerUp = 24),
                    vote(19_900, distance = 0, runnerUp = 24),
                    vote(20_050, distance = 0, runnerUp = 24),
                    vote(19_950, distance = 0, runnerUp = 24),
                ),
                toleranceMs = 8_000L,
            ),
        )
        assertTrue(strong.confidence > weak.confidence, "${strong.confidence} !> ${weak.confidence}")
        assertTrue(strong.confidence in 0f..1f && weak.confidence in 0f..1f)
    }

    @Test
    fun `drift is reported only when the bursts span enough playback to mean anything`() {
        val tooClose = assertNotNull(
            OffsetConsensus.cluster(
                listOf(
                    vote(20_000, playbackMs = 0),
                    vote(20_000, playbackMs = 5_000),
                    vote(20_000, playbackMs = 10_000),
                ),
                toleranceMs = 8_000L,
            ),
        )
        assertNull(tooClose.driftPpm, "a slope fitted across 10s of playback is noise")

        val spread = assertNotNull(
            OffsetConsensus.cluster(
                listOf(
                    vote(20_000, playbackMs = 0),
                    vote(20_000, playbackMs = 600_000),
                    vote(20_000, playbackMs = 1_200_000),
                ),
                toleranceMs = 8_000L,
            ),
        )
        assertEquals(0.0, assertNotNull(spread.driftPpm), 1e-9, "a constant offset has no drift")
    }

    @Test
    fun `a steadily growing offset reads as positive drift`() {
        // 10s of extra offset accumulated over 1000s of playback is 1% = 10,000 ppm.
        val cluster = assertNotNull(
            OffsetConsensus.cluster(
                listOf(
                    vote(20_000, playbackMs = 0),
                    vote(25_000, playbackMs = 500_000),
                    vote(30_000, playbackMs = 1_000_000),
                ),
                toleranceMs = 8_000L,
            ),
        )
        assertEquals(10_000.0, assertNotNull(cluster.driftPpm), 1.0)
    }
}
