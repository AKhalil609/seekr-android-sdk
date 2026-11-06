package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import tv.seekr.previews.core.SeekrAuth

/**
 * [LookupClient.fetchHashes] on inputs that never reach the network.
 *
 * Offset matching runs on the same coroutine a player drives playback from, so the one
 * outcome it may never produce is a thrown exception. OkHttp's `url(String)` throws on
 * anything that is not a valid http(s) URL, which makes a malformed `hashes_url` from a
 * future backend regression a crash rather than a declined match — unless the request is
 * built inside the guard, which is what these pin.
 */
class LookupClientHashesTest {

    private val client = LookupClient(
        auth = SeekrAuth.ApiKey("sk_test"),
        baseUrl = "https://api.seekr.tv/",
        http = OkHttpClient(),
    )

    @Test
    fun `a malformed hashes url declines instead of throwing`() = runBlocking {
        val malformed = listOf(
            "sprites.seekr.tv/1/2/hashes.bin", // no scheme
            "/1318413/6041000/hashes.bin", // relative — a plausible backend regression
            "ftp://sprites.seekr.tv/hashes.bin",
            "https://[bad/hashes.bin",
            "   ",
        )
        for (url in malformed) {
            assertIs<HashFetch.Missing>(client.fetchHashes(url), "should decline: '$url'")
        }
    }
}
