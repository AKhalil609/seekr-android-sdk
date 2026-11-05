package tv.seekr.previews.core.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.seekr.previews.core.MatchStatus

/** Fetches a version's `hashes.bin`. Abstracted from [LookupClient] so the core matcher is testable without HTTP. */
internal fun interface HashFetcher {
    suspend fun fetch(url: String): HashFetch
}

/** Outcome of [HashSource.load]. */
internal sealed interface HashLoad {
    class Ready(val hashes: LongArray) : HashLoad

    /** [status] says what went wrong; [terminal] says whether asking again could ever help. */
    class Failed(val status: MatchStatus, val terminal: Boolean) : HashLoad
}

/**
 * Lazily downloads, validates and caches one version's perceptual hashes.
 *
 * ### Lazy is a requirement, not an optimisation
 * Nothing here may run during `loadTrack`. Preview rendering is on the critical path of a
 * user dragging a scrubber, offset matching is a background nicety, and a client that
 * waited on a several-KB sidecar before it could draw its first thumbnail would have traded
 * a visible, universal regression for an invisible, occasional improvement. So the first
 * [load] is the first `matchOffset`, and this object shares no lock with the cue lookup.
 *
 * ### Failure classification
 * Three outcomes, because they demand three different reactions:
 * - **Ready** — cached for the life of the track; never re-fetched.
 * - **Failed, terminal** — no hashes published, an unknown reduction, or a length that does
 *   not line up with the cues. None of these change by asking again, so the caller should
 *   stop capturing frames altogether rather than burn battery losing the same fight.
 * - **Failed, retryable** — a 5xx or a dropped connection. Retried up to [maxAttempts]
 *   times across calls, then latched terminal.
 */
internal class HashSource(
    private val hashesUrl: String,
    private val hashAlgo: String,
    private val declaredCount: Int,
    private val expectedCount: Int,
    private val fetcher: HashFetcher,
    private val maxAttempts: Int = 3,
) {
    private val mutex = Mutex()
    private var cached: LongArray? = null
    private var latched: MatchStatus? = null
    private var attempts = 0

    /**
     * Returns the hashes, downloading them on first use. Concurrent first callers serialise
     * on [mutex] and only one download happens; every later caller returns from cache.
     */
    suspend fun load(): HashLoad = mutex.withLock {
        cached?.let { return@withLock HashLoad.Ready(it) }
        latched?.let { return@withLock HashLoad.Failed(it, terminal = true) }

        preflight()?.let { status ->
            latched = status
            return@withLock HashLoad.Failed(status, terminal = true)
        }

        attempts++
        when (val fetched = fetcher.fetch(hashesUrl)) {
            is HashFetch.Bytes -> {
                val hashes = unpack(fetched.data)
                if (hashes == null || hashes.size != expectedCount) {
                    latched = MatchStatus.HASH_COUNT_MISMATCH
                    HashLoad.Failed(MatchStatus.HASH_COUNT_MISMATCH, terminal = true)
                } else {
                    cached = hashes
                    HashLoad.Ready(hashes)
                }
            }
            // A 4xx: the sidecar is absent or the signature is spent. Neither is going to
            // resolve itself while this track is alive, so treat it as "not published".
            is HashFetch.Missing -> {
                latched = MatchStatus.NO_HASHES_PUBLISHED
                HashLoad.Failed(MatchStatus.NO_HASHES_PUBLISHED, terminal = true)
            }
            is HashFetch.Transient -> {
                val exhausted = attempts >= maxAttempts
                if (exhausted) latched = MatchStatus.HASH_FETCH_FAILED
                HashLoad.Failed(MatchStatus.HASH_FETCH_FAILED, terminal = exhausted)
            }
        }
    }

    /** Everything that can be ruled out without spending a request. */
    private fun preflight(): MatchStatus? = when {
        expectedCount <= 0 -> MatchStatus.EMPTY_TRACK
        hashesUrl.isEmpty() -> MatchStatus.NO_HASHES_PUBLISHED
        hashAlgo != DHash.ALGO -> MatchStatus.UNSUPPORTED_ALGO
        // The count the lookup declared already disagrees with the cue list, so the download
        // cannot produce a usable list whatever it contains. 0 means "not reported".
        declaredCount > 0 && declaredCount != expectedCount -> MatchStatus.HASH_COUNT_MISMATCH
        else -> null
    }

    /** `count * 8` bytes, big-endian, no header — see `zoetrope/fingerprint.py`. */
    private fun unpack(data: ByteArray): LongArray? {
        if (data.isEmpty() || data.size % Long.SIZE_BYTES != 0) return null
        val out = LongArray(data.size / Long.SIZE_BYTES)
        var at = 0
        for (i in out.indices) {
            var value = 0L
            repeat(Long.SIZE_BYTES) {
                value = (value shl 8) or (data[at++].toLong() and 0xFF)
            }
            out[i] = value
        }
        return out
    }
}
