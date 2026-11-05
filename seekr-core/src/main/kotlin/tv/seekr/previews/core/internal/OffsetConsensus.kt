package tv.seekr.previews.core.internal

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Matching one burst of frames against the hash list, and reaching consensus across bursts.
 *
 * Both halves mirror `Zoetrope/scripts/verify_sampling.py` (`resolve` and `cluster_vote`),
 * which is where the published accuracy figures come from. The rules are not
 * interchangeable with plausible alternatives: swapping either one silently invalidates the
 * measurement that says this feature is safe to ship.
 */
internal object OffsetConsensus {

    /** How many of the bursts in the window must agree. 2-of-5 mis-syncs 19% on a degenerate title; 3-of-5 mis-syncs 0%. */
    const val REQUIRED_AGREEMENT = 3

    /** How many recent bursts are weighed at once. */
    const val WINDOW = 5

    /**
     * Extra tolerance beyond half a cue interval, absorbing the +/-3s of cue jitter carried
     * by every version generated before 2026-09-07: those VTTs recorded grid times rather
     * than the real keyframe-snapped extraction times, and the sources are deleted, so the
     * true times are unrecoverable. Matching is unaffected — finding a tile never reads a
     * timestamp — but every offset *derived* from a match inherits the error.
     */
    const val SNAP_JITTER_MS = 3_000L

    /** Shortest playback span across which a drift slope means anything. See [Cluster.driftPpm]. */
    private const val MIN_DRIFT_SPAN_MS = 60_000L

    /** Assumed cue spacing for a track too short to measure one. The generator's default. */
    const val DEFAULT_CUE_INTERVAL_MS = 10_000L

    /** Bit distance at which two frames are certainly not the same picture; scales [Cluster.confidence]. */
    private const val DISTANCE_CEILING_BITS = 20f

    /** Runner-up gap at which a match counts as unambiguous. */
    private const val MARGIN_CEILING_BITS = 12f

    /** `+/-(interval / 2 + 3000)` — the window inside which two estimates count as agreeing. */
    fun toleranceMs(cueIntervalMs: Long): Long = cueIntervalMs / 2 + SNAP_JITTER_MS

    /** Best hash for [probe]: its index, its distance, and the distance to the next best. */
    class Found(val index: Int, val distance: Int, val runnerUp: Int)

    /**
     * Linear scan for the closest hash. Mirrors `verify_fingerprint._search`.
     *
     * There is deliberately **no maximum accepted distance**. The reference has none, and
     * adding one would be an untested second gate on top of the measured one: a frame with
     * no good match produces an estimate that disagrees with everything else and is thrown
     * out by the clustering, which is the mechanism that was actually measured at 0.0%
     * wrong. Rejecting early would change the acceptance profile without evidence.
     */
    fun search(hashes: LongArray, probe: Long): Found? {
        if (hashes.isEmpty()) return null
        var best = 65
        var bestAt = -1
        var second = 65
        for (index in hashes.indices) {
            val d = DHash.hamming(probe, hashes[index])
            if (d < best) {
                second = best
                best = d
                bestAt = index
            } else if (d < second) {
                second = d
            }
        }
        return if (bestAt < 0) null else Found(bestAt, best, second)
    }

    /** One burst reduced to a single estimate: the frame in it whose match was closest. */
    class Vote(
        val offsetMs: Long,
        val playbackMs: Long,
        val distance: Int,
        val runnerUp: Int,
    )

    /** The agreeing group of estimates that a match is built from. */
    class Cluster(
        val offsetMs: Long,
        val members: List<Vote>,
        val considered: Int,
        val confidence: Float,
        val medianDistance: Int,
        val medianRunnerUp: Int,
        val spreadMs: Long,
        val driftPpm: Double?,
    )

    /**
     * Largest group of estimates agreeing within [toleranceMs], or `null` if none reaches
     * [REQUIRED_AGREEMENT].
     *
     * Agreement is a **window**, not equality. Off-grid estimates are near-misses of each
     * other rather than repeats, so quantising them into buckets and demanding an exact
     * match scored 7.6% correct against 24.4% for this. Worse, bucket voting can never
     * *decline*: five estimates always collapse into two buckets and one always wins, so it
     * answers even when it has nothing — and a wrong answer is the one outcome the user
     * cannot recover from.
     */
    fun cluster(votes: List<Vote>, toleranceMs: Long): Cluster? {
        if (votes.size < REQUIRED_AGREEMENT) return null
        var best: List<Vote> = emptyList()
        for (pivot in votes.sortedBy { it.offsetMs }) {
            val members = votes.filter { abs(it.offsetMs - pivot.offsetMs) <= toleranceMs }
            if (members.size > best.size) best = members
        }
        if (best.size < REQUIRED_AGREEMENT) return null

        val offsets = best.map { it.offsetMs }
        val medianDistance = medianOf(best.map { it.distance.toLong() }).toInt()
        val medianRunnerUp = medianOf(best.map { it.runnerUp.toLong() }).toInt()
        val spread = (offsets.max() - offsets.min())
        val centre = medianOf(offsets)
        val deviation = medianOf(offsets.map { abs(it - centre) })

        return Cluster(
            offsetMs = centre,
            members = best,
            considered = votes.size,
            confidence = confidence(
                agreeing = best.size,
                medianDistance = medianDistance,
                medianRunnerUp = medianRunnerUp,
                deviationMs = deviation,
                toleranceMs = toleranceMs,
            ),
            medianDistance = medianDistance,
            medianRunnerUp = medianRunnerUp,
            spreadMs = spread,
            driftPpm = driftPpm(best),
        )
    }

    /**
     * A documented blend of the four things that distinguish a strong match from a
     * just-barely one. Reported, never used as a gate — see [tv.seekr.previews.core.OffsetMatch.confidence].
     */
    private fun confidence(
        agreeing: Int,
        medianDistance: Int,
        medianRunnerUp: Int,
        deviationMs: Long,
        toleranceMs: Long,
    ): Float {
        val agreement =
            (agreeing - REQUIRED_AGREEMENT + 1).toFloat() / (WINDOW - REQUIRED_AGREEMENT + 1)
        val quality = 1f - (medianDistance / DISTANCE_CEILING_BITS)
        val margin = (medianRunnerUp - medianDistance) / MARGIN_CEILING_BITS
        val tightness =
            if (toleranceMs <= 0) 1f else 1f - (deviationMs.toFloat() / toleranceMs)
        return (
            0.40f * agreement.coerceIn(0f, 1f) +
                0.25f * quality.coerceIn(0f, 1f) +
                0.20f * margin.coerceIn(0f, 1f) +
                0.15f * tightness.coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
    }

    /**
     * Least-squares slope of estimate against playback position, in parts per million.
     *
     * `null` unless the agreeing bursts span [MIN_DRIFT_SPAN_MS], because the estimates are
     * quantised to the cue grid: the smallest slope distinguishable from quantisation noise
     * is roughly `cueIntervalMs / spanMs`, so a slope fitted across 20 seconds of playback
     * is noise with a decimal point. Even at a minute this only sees gross drift — PAL
     * speed-up, at ~40,000 ppm, is the case it is there to catch.
     */
    private fun driftPpm(members: List<Vote>): Double? {
        if (members.size < REQUIRED_AGREEMENT) return null
        val span = members.maxOf { it.playbackMs } - members.minOf { it.playbackMs }
        if (span < MIN_DRIFT_SPAN_MS) return null
        val meanX = members.sumOf { it.playbackMs }.toDouble() / members.size
        val meanY = members.sumOf { it.offsetMs }.toDouble() / members.size
        var num = 0.0
        var den = 0.0
        for (v in members) {
            val dx = v.playbackMs - meanX
            num += dx * (v.offsetMs - meanY)
            den += dx * dx
        }
        if (den == 0.0) return null
        return (num / den) * 1_000_000.0
    }

    private fun medianOf(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToLong()
    }
}
