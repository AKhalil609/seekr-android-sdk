package tv.seekr.previews.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.seekr.previews.core.internal.DHash
import tv.seekr.previews.core.internal.HashLoad
import tv.seekr.previews.core.internal.HashSource
import tv.seekr.previews.core.internal.OffsetConsensus
import tv.seekr.previews.core.internal.VttCue

/**
 * An offset-matching session that accumulates evidence across bursts.
 *
 * Obtain one from [PreviewTrack.offsetMatcher]. Feed it a burst whenever your player has
 * managed to capture one and ask, in the same call, whether that was enough:
 *
 * ```
 * val matcher = track.offsetMatcher()
 * // ... as playback proceeds, roughly every 30-60s:
 * val match = matcher.offer(Sample(frames))
 * if (match != null) {
 *     track.offsetMs = match.offsetMs
 * } else if (matcher.isTerminal) {
 *     stopCapturingFrames()   // nothing will ever come of it — see MatchStatus
 * }
 * ```
 *
 * ### Why the API is incremental
 * A player that cannot seek — a P2P stream, where an arbitrary seek lands on pieces nobody
 * has downloaded — can only collect frames as playback reaches them. Requiring all five
 * bursts in one call would push a five-minute buffering problem onto every caller, and each
 * would solve it slightly differently. The session owns that buffer instead, and it is
 * bounded: only the most recent [WINDOW_SIZE] bursts are ever weighed.
 *
 * ### When it answers
 * As soon as [REQUIRED_AGREEMENT] bursts inside that window agree — so possibly on the third
 * burst. Answering early is not a weaker result: three agreeing out of three is strictly
 * stronger evidence than three agreeing out of five, and the same tolerance window applies
 * either way.
 *
 * Safe to call [offer] from any coroutine; calls serialise internally. Hashing runs on
 * [Dispatchers.Default] and the one-off hash download on `Dispatchers.IO`, so nothing here
 * blocks a caller's thread, and none of it shares a lock with [PreviewTrack.tileAt].
 */
class OffsetMatcher internal constructor(
    private val cues: List<VttCue>,
    private val hashes: HashSource?,
    /** Cue interval this session's tolerance window is derived from. */
    val cueIntervalMs: Long,
) {
    private val lock = Mutex()
    private val votes = ArrayDeque<OffsetConsensus.Vote>()

    // Volatile because isTerminal is read from outside the lock — typically from whatever
    // thread owns frame capture, deciding whether to keep capturing at all. A plain var
    // could stay false there indefinitely after the matcher gave up, which is precisely the
    // battery drain the flag exists to prevent.
    @Volatile
    private var terminal = false

    // The box every frame is letterboxed into before hashing. Taken from the cues rather
    // than assumed, because it is the geometry the producer hashed its tiles at, and a
    // future change to the sprite grid would otherwise silently break every match.
    private val tileWidth: Int =
        cues.firstOrNull()?.tile?.w?.takeIf { it > 0 } ?: DHash.DEFAULT_TILE_WIDTH
    private val tileHeight: Int =
        cues.firstOrNull()?.tile?.h?.takeIf { it > 0 } ?: DHash.DEFAULT_TILE_HEIGHT

    @Volatile
    private var currentStatus: MatchStatus =
        if (cues.isEmpty()) MatchStatus.EMPTY_TRACK else MatchStatus.NEED_MORE_BURSTS

    @Volatile
    private var currentMatch: OffsetMatch? = null

    @Volatile
    private var accepted: Int = 0

    init {
        if (cues.isEmpty()) terminal = true
    }

    /** Why there is no match yet, or [MatchStatus.MATCHED]. See [MatchStatus]. */
    val status: MatchStatus get() = currentStatus

    /**
     * The best match the evidence currently supports, or `null`.
     *
     * Withdrawn if later bursts push the agreeing ones out of the window and consensus is
     * lost, so this reflects what the matcher stands behind *now*, not what it once said.
     *
     * Read [status] first and this second if you poll rather than using the value returned
     * by [offer]: the two are independent volatile fields rather than one snapshot, so that
     * order is what guarantees a non-`null` match whenever the status reads
     * [MatchStatus.MATCHED], and never a withdrawn one otherwise. Reading them the other way
     * round can momentarily pair a fresh match with a stale status, which is harmless but not
     * what the contract promises.
     */
    val match: OffsetMatch? get() = currentMatch

    /** How many bursts have contributed an estimate. Bursts with no usable frames do not count. */
    val burstsAccepted: Int get() = accepted

    /**
     * `true` when no future burst can change the outcome — the version publishes no hashes,
     * uses a reduction this SDK does not implement, has a hash list that does not line up
     * with its cues, or could not be downloaded after repeated attempts.
     *
     * Stop capturing frames when this is `true`. Frame capture costs battery and a GPU
     * readback per frame, and repeating it for a title that structurally cannot match is
     * pure loss — the "fail once, not forever" rule.
     */
    val isTerminal: Boolean get() = terminal

    /**
     * Adds one burst and returns the current match, or `null` if there still isn't one.
     *
     * Within the burst only the single best-matching frame is kept; the others decoded
     * moments the sprite source has no tile for, and counting them would add estimates
     * derived from frames that resemble nothing, diluting the real signal.
     *
     * Frames whose [PixelSource] is empty are ignored, and a burst with no usable frames is
     * not counted at all — [burstsAccepted] is the honest measure of how much evidence this
     * session actually has.
     */
    suspend fun offer(sample: Sample): OffsetMatch? = lock.withLock {
        if (terminal) return@withLock currentMatch
        val source = hashes ?: run {
            // Write terminal before status, so a caller that observes a terminal status is
            // guaranteed to see the flag that tells it to stop.
            terminal = true
            currentStatus = MatchStatus.NO_HASHES_PUBLISHED
            return@withLock null
        }

        val list = when (val loaded = source.load()) {
            is HashLoad.Ready -> loaded.hashes
            is HashLoad.Failed -> {
                terminal = loaded.terminal
                currentStatus = loaded.status
                return@withLock null
            }
        }

        val vote = withContext(Dispatchers.Default) { resolve(sample, list) }
        if (vote != null) {
            accepted++
            votes.addLast(vote)
            while (votes.size > WINDOW_SIZE) votes.removeFirst()
        }

        val cluster = OffsetConsensus.cluster(
            votes = votes.toList(),
            toleranceMs = OffsetConsensus.toleranceMs(cueIntervalMs),
        )
        if (cluster == null) {
            // Drop the previous answer with it. Bursts roll out of the window, so a match
            // made early can stop being supported by the evidence now in hand — and a
            // caller reading `match` rather than this return value would otherwise apply an
            // offset the matcher has since withdrawn.
            currentMatch = null
            currentStatus =
                if (votes.size < REQUIRED_AGREEMENT) MatchStatus.NEED_MORE_BURSTS
                else MatchStatus.NO_CONSENSUS
            return@withLock null
        }

        val result = OffsetMatch(
            offsetMs = cluster.offsetMs,
            confidence = cluster.confidence,
            agreeingBursts = cluster.members.size,
            burstsConsidered = cluster.considered,
            medianDistanceBits = cluster.medianDistance,
            medianRunnerUpBits = cluster.medianRunnerUp,
            spreadMs = cluster.spreadMs,
            driftPpm = cluster.driftPpm,
        )
        currentMatch = result
        currentStatus = MatchStatus.MATCHED
        result
    }

    /** Feeds several bursts in order and returns the match once any of them produces one. */
    suspend fun offerAll(samples: List<Sample>): OffsetMatch? {
        var result: OffsetMatch? = null
        for (sample in samples) {
            result = offer(sample)
            if (isTerminal) break
        }
        return result
    }

    /**
     * Discards the accumulated bursts and any match, so a session can be reused — after a
     * user override, say. Does **not** re-download the hashes, and does not clear a terminal
     * status: neither of those changes by asking again.
     */
    suspend fun reset() = lock.withLock {
        votes.clear()
        accepted = 0
        currentMatch = null
        if (!terminal) currentStatus = MatchStatus.NEED_MORE_BURSTS
    }

    /**
     * Hashes each frame of the burst, finds its closest sprite hash, and keeps the single
     * closest across the burst.
     *
     * The estimate is `cues[index].startMs - frame.playbackMs`, matching the
     * `previewTime = playbackTime + offsetMs` convention. Note it reads the matched cue's
     * recorded start rather than `index * interval`: cue starts are snapped to real
     * keyframes and are not evenly spaced, so reconstructing the time arithmetically would
     * introduce an error that no amount of consensus can remove.
     */
    private fun resolve(sample: Sample, list: LongArray): OffsetConsensus.Vote? {
        var best: OffsetConsensus.Vote? = null
        var bestDistance = Int.MAX_VALUE
        for (frame in sample.frames) {
            val image = frame.image
            if (image.width <= 0 || image.height <= 0) continue
            // A PixelSource is the caller's code reading the caller's buffer — an Android
            // Bitmap recycled by the player mid-match throws from getPixels, for instance.
            // Dropping that frame costs one estimate; letting it propagate would surface a
            // background nicety as a crash in the coroutine that drives playback.
            val probe = runCatching { DHash.hashOrNull(image, tileWidth, tileHeight) }.getOrNull()
                ?: continue
            val found = OffsetConsensus.search(list, probe) ?: continue
            if (found.index !in cues.indices) continue
            if (found.distance < bestDistance) {
                bestDistance = found.distance
                best = OffsetConsensus.Vote(
                    offsetMs = cues[found.index].startMs - frame.playbackMs,
                    playbackMs = frame.playbackMs,
                    distance = found.distance,
                    runnerUp = found.runnerUp,
                )
            }
        }
        return best
    }

    companion object {
        /** Bursts that must agree before an offset is returned. */
        const val REQUIRED_AGREEMENT: Int = OffsetConsensus.REQUIRED_AGREEMENT

        /** How many of the most recent bursts are weighed at once. */
        const val WINDOW_SIZE: Int = OffsetConsensus.WINDOW

        /** Frames a caller should put in each burst. See [PreviewTrack.matchOffset]. */
        const val RECOMMENDED_FRAMES_PER_BURST: Int = 4
    }
}
