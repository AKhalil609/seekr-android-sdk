package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tv.seekr.previews.core.RejectReason
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.TrackResult

/** `/sprites` against canned responses: how each refusal is told apart, and what is sent. */
class LookupClientTest {
    private val movie = SeekrContent.Movie(imdbId = "tt0133093")
    private val seen = mutableListOf<Request>()

    private fun client(
        auth: SeekrAuth,
        secret: ByteArray? = null,
        clock: () -> Long = { 1_767_323_045_000L },
        answer: (Request) -> Response,
    ): LookupClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen += chain.request()
            answer(chain.request())
        }).build()
        return LookupClient(auth, "https://api.seekr.tv/", http, secret, clock)
    }

    private fun reply(req: Request, code: Int, body: String = "", vararg headers: Pair<String, String>): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun failed(c: LookupClient): TrackResult = runBlocking {
        (c.loadCues(movie, 8_280_000, 0) as CueLoad.Failed).outcome
    }

    @Test
    fun `a bearer is sent as Authorization and an api key as X-API-Key`() {
        failed(client(SeekrAuth.Bearer { "tok" }) { reply(it, 404) })
        failed(client(SeekrAuth.ApiKey("sk_live_x")) { reply(it, 404) })
        assertEquals("Bearer tok", seen[0].header("Authorization"))
        assertNull(seen[0].header("X-API-Key"))
        assertEquals("sk_live_x", seen[1].header("X-API-Key"))
    }

    @Test
    fun `a signed-out bearer never touches the network`() {
        val result = failed(client(SeekrAuth.Bearer { null }) { error("no request expected") })
        assertEquals(TrackResult.Rejected(RejectReason.SignedOut), result)
        assertEquals(0, seen.size)
    }

    @Test
    fun `refusals are told apart`() {
        fun outcome(code: Int, body: String = "", vararg h: Pair<String, String>) =
            failed(client(SeekrAuth.ApiKey("k")) { reply(it, code, body, *h) })
        assertEquals(TrackResult.NoPreviews, outcome(404))
        assertEquals(TrackResult.Rejected(RejectReason.Unauthorized), outcome(401))
        assertEquals(TrackResult.Rejected(RejectReason.AccountSuspended), outcome(403, """{"error":"account suspended"}"""))
        assertEquals(TrackResult.Rejected(RejectReason.Disabled), outcome(410, """{"error":"sprites_disabled"}"""))
        assertEquals(TrackResult.RateLimited(7), outcome(429, "", "Retry-After" to "7"))
        assertEquals(TrackResult.Unavailable, outcome(503))
    }

    @Test
    fun `an attestation is sent only when there is a secret`() {
        failed(client(SeekrAuth.ApiKey("k")) { reply(it, 404) })
        failed(client(SeekrAuth.ApiKey("k"), secret = ByteArray(32) { 1 }) { reply(it, 404) })
        assertNull(seen[0].header("X-Playback-Attestation"))
        assertNotNull(seen[1].header("X-Playback-Attestation"))
    }

    @Test
    fun `a clock the server rejects is corrected from its Date header and retried once`() {
        val deviceNow = 1_767_323_045_000L
        val serverNow = deviceNow + 3_600_000L
        val date = "Fri, 02 Jan 2026 04:04:05 GMT" // = serverNow
        var calls = 0
        val c = client(SeekrAuth.ApiKey("k"), secret = ByteArray(32) { 1 }, clock = { deviceNow }) {
            calls++
            if (calls == 1) reply(it, 403, """{"error":"invalid playback attestation token"}""", "Date" to date) else reply(it, 404)
        }
        assertEquals(TrackResult.NoPreviews, failed(c))
        assertEquals(2, calls)
        assertEquals(
            PlaybackAttestation.mint(ByteArray(32) { 1 }, "movie:imdb:tt0133093", 0, serverNow),
            seen[1].header("X-Playback-Attestation"),
        )
    }

    @Test
    fun `an attestation refusal that clock skew does not explain is final`() {
        var calls = 0
        val c = client(SeekrAuth.ApiKey("k"), secret = ByteArray(32) { 1 }) {
            calls++
            reply(it, 403, """{"error":"invalid playback attestation token"}""", "Date" to "Fri, 02 Jan 2026 03:04:05 GMT")
        }
        assertEquals(TrackResult.Rejected(RejectReason.Attestation), failed(c))
        assertEquals(1, calls)
    }

    @Test
    fun `the position bound into the token is clamped to the duration`() {
        val secret = ByteArray(32) { 1 }
        runBlocking {
            client(SeekrAuth.ApiKey("k"), secret) { reply(it, 404) }.loadCues(movie, 1_000, 99_999)
        }
        assertEquals(
            PlaybackAttestation.mint(secret, "movie:imdb:tt0133093", 1_000, 1_767_323_045_000L),
            seen.single().header("X-Playback-Attestation"),
        )
        assertIs<CueLoad.Failed>(runBlocking { client(SeekrAuth.ApiKey("k")) { reply(it, 404) }.loadCues(movie, 1_000, 0) })
    }
}
