package tv.seekr.previews.core.internal

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tv.seekr.previews.core.MatchStatus

/**
 * How the hash sidecar is loaded, and — mostly — how it fails.
 *
 * The distinction these tests exist for is between "asking again might help" and "asking
 * again cannot help". Getting it wrong in one direction disables the feature after a single
 * dropped packet; in the other it re-requests a URL that will 404 for the rest of the
 * session, on every burst, on a device that may be running on battery.
 */
class HashSourceTest {

    private val url = "https://sprites.seekr.tv/1318413/6041000/hashes.bin?exp=1&sig=x"

    private fun pack(values: LongArray): ByteArray {
        val out = ByteArray(values.size * 8)
        var at = 0
        for (v in values) for (shift in 56 downTo 0 step 8) out[at++] = (v ushr shift).toByte()
        return out
    }

    private fun source(
        url: String = this.url,
        algo: String = DHash.ALGO,
        declaredCount: Int = 3,
        expectedCount: Int = 3,
        fetcher: HashFetcher,
    ) = HashSource(url, algo, declaredCount, expectedCount, fetcher)

    @Test
    fun `hashes are decoded big-endian, one per eight bytes`() = runBlocking {
        val values = longArrayOf(0L, 1L, -1L, 0x0123456789ABCDEFL, Long.MIN_VALUE)
        val source = source(declaredCount = values.size, expectedCount = values.size) {
            HashFetch.Bytes(pack(values))
        }
        val ready = assertIs<HashLoad.Ready>(source.load())
        assertTrue(values.contentEquals(ready.hashes), ready.hashes.joinToString())
    }

    @Test
    fun `the sidecar is downloaded once and then cached`() = runBlocking {
        val calls = AtomicInteger()
        val source = source {
            calls.incrementAndGet()
            HashFetch.Bytes(pack(longArrayOf(1, 2, 3)))
        }
        repeat(5) { assertIs<HashLoad.Ready>(source.load()) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `concurrent first calls share a single download`() = runBlocking {
        val calls = AtomicInteger()
        val source = source {
            calls.incrementAndGet()
            delay(50) // long enough that a second caller would race in without the lock
            HashFetch.Bytes(pack(longArrayOf(1, 2, 3)))
        }
        val results = (0 until 8).map { async(Dispatchers.Default) { source.load() } }.awaitAll()
        assertEquals(1, calls.get(), "a burst of concurrent matches must not fan out to 8 requests")
        results.forEach { assertIs<HashLoad.Ready>(it) }
    }

    @Test
    fun `a version with no hashes is terminal and costs no request`() = runBlocking {
        val calls = AtomicInteger()
        val source = source(url = "") { calls.incrementAndGet(); HashFetch.Transient }
        val failed = assertIs<HashLoad.Failed>(source.load())
        assertEquals(MatchStatus.NO_HASHES_PUBLISHED, failed.status)
        assertTrue(failed.terminal)
        assertEquals(0, calls.get())
    }

    @Test
    fun `an unknown reduction is terminal and costs no request`() = runBlocking {
        // Hashes from a reduction we do not implement are not "slightly different" — they are
        // a different measurement entirely, and comparing them would yield confident nonsense.
        val calls = AtomicInteger()
        val source = source(algo = "dhash16") { calls.incrementAndGet(); HashFetch.Transient }
        val failed = assertIs<HashLoad.Failed>(source.load())
        assertEquals(MatchStatus.UNSUPPORTED_ALGO, failed.status)
        assertTrue(failed.terminal)
        assertEquals(0, calls.get())
    }

    @Test
    fun `a declared count that disagrees with the cues is terminal and costs no request`() =
        runBlocking {
            val calls = AtomicInteger()
            val source = source(declaredCount = 604, expectedCount = 600) {
                calls.incrementAndGet()
                HashFetch.Transient
            }
            val failed = assertIs<HashLoad.Failed>(source.load())
            assertEquals(MatchStatus.HASH_COUNT_MISMATCH, failed.status)
            assertEquals(0, calls.get(), "the download cannot fix a list that is the wrong length")
        }

    @Test
    fun `a count of zero means unreported, not empty`() = runBlocking {
        // Older backends omit hash_count. That must not be read as "this version has no
        // hashes", or the feature would be off everywhere the field predates the client.
        val source = source(declaredCount = 0) { HashFetch.Bytes(pack(longArrayOf(1, 2, 3))) }
        assertIs<HashLoad.Ready>(source.load())
        Unit
    }

    @Test
    fun `a payload whose length does not match the cue list is terminal`() = runBlocking {
        // Cues and hashes from different generations: index N would no longer be cue N, and
        // a shifted index produces a wrong offset that looks exactly like a right one.
        val source = source(declaredCount = 0) { HashFetch.Bytes(pack(longArrayOf(1, 2))) }
        val failed = assertIs<HashLoad.Failed>(source.load())
        assertEquals(MatchStatus.HASH_COUNT_MISMATCH, failed.status)
        assertTrue(failed.terminal)
    }

    @Test
    fun `a truncated payload is terminal`() = runBlocking {
        val source = source(declaredCount = 0) { HashFetch.Bytes(ByteArray(20)) }
        val failed = assertIs<HashLoad.Failed>(source.load())
        assertEquals(MatchStatus.HASH_COUNT_MISMATCH, failed.status)
    }

    @Test
    fun `a 4xx is treated as not published rather than retried`() = runBlocking {
        val calls = AtomicInteger()
        val source = source { calls.incrementAndGet(); HashFetch.Missing }
        val failed = assertIs<HashLoad.Failed>(source.load())
        assertEquals(MatchStatus.NO_HASHES_PUBLISHED, failed.status)
        assertTrue(failed.terminal)
        source.load()
        assertEquals(1, calls.get(), "a spent signature or absent sidecar will not appear on retry")
    }

    @Test
    fun `a network blip is retried, then latched`() = runBlocking {
        val calls = AtomicInteger()
        val source = source { calls.incrementAndGet(); HashFetch.Transient }

        val first = assertIs<HashLoad.Failed>(source.load())
        assertEquals(MatchStatus.HASH_FETCH_FAILED, first.status)
        assertFalse(first.terminal, "one dropped connection must not disable the feature")

        assertFalse(assertIs<HashLoad.Failed>(source.load()).terminal)
        assertTrue(assertIs<HashLoad.Failed>(source.load()).terminal, "third strike")

        source.load()
        source.load()
        assertEquals(3, calls.get(), "no further requests once the retry budget is spent")
    }

    @Test
    fun `a retry that succeeds is used`() = runBlocking {
        val calls = AtomicInteger()
        val source = source {
            if (calls.incrementAndGet() == 1) HashFetch.Transient
            else HashFetch.Bytes(pack(longArrayOf(1, 2, 3)))
        }
        assertIs<HashLoad.Failed>(source.load())
        assertIs<HashLoad.Ready>(source.load())
        Unit
    }
}
