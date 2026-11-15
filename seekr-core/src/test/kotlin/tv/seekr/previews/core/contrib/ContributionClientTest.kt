package tv.seekr.previews.core.contrib

import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent

/** Request shapes and error mapping of [ContributionClient], against canned responses. */
class ContributionClientTest {
    private class Seen(val request: Request, val body: ByteArray)

    private val seen = mutableListOf<Seen>()
    private val movie = SeekrContent.Movie(imdbId = "tt0133093", tmdbId = 603)
    private val fp = Fp.fromTorrent("0123456789ABCDEF0123456789ABCDEF01234567", 2)

    // 3 cues of a 25 s file; the exact bytes do not matter to the client, but n_slots does (header bytes 12..15).
    private val nvmt = NvmtWriter(3).also { w ->
        val jpeg = ByteArray(200).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        w.add(0, 0, jpeg)
        w.add(2, 20_500, jpeg)
    }.build()

    private fun reply(req: Request, code: Int, body: String = "", vararg headers: Pair<String, String>): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun client(
        auth: SeekrAuth = SeekrAuth.ApiKey("sk_live_x"),
        store: InstallationStore = InMemoryInstallationStore(),
        answer: (Request) -> Response,
    ): ContributionClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val buffer = Buffer()
            req.body?.writeTo(buffer)
            seen += Seen(req, buffer.readByteArray())
            answer(req)
        }).build()
        return ContributionClient.create(auth, "https://api.seekr.tv/", http, store)
    }

    private val mintBody = """{"installation":"ci1_token","usable_after":"2026-11-15T10:00:00.250Z"}"""
    private val submissionBody =
        """{"id":"0b0d1f9e-1111-2222-3333-444444444444","status":"pending","job_status":"collecting","created_at":"x","expires_at":"y","needed":"Ag"}"""

    /** Routes the installation mint and the submission separately. */
    private fun submitting(submit: (Request) -> Response): (Request) -> Response = { req ->
        if (req.url.encodedPath == "/v1/contrib/installations") reply(req, 201, mintBody) else submit(req)
    }

    private fun submit(c: ContributionClient, content: SeekrContent = movie, dryRun: Boolean = false) =
        runBlocking { c.submit(content, 25_000, fp, nvmt, "seekr-tv/1.4.0", releaseHintHash = null, dryRun = dryRun) }

    private fun error(result: SubmitResult): ContributionError = (result as SubmitResult.Failure).error

    // ---- needs ----

    @Test
    fun `needs for a movie sends the envelope, duration and fp with the api key`() {
        val c = client { reply(it, 200, """{"generate":false,"reason":"have_exact","grid_ms":10000,"n_slots":3,"job_duration_ms":25000,"needed":"","overlap":"","upload_after_cues":30}""") }
        runBlocking { c.needs(movie, 25_000, fp) }
        val req = seen.single().request
        assertEquals("GET", req.method)
        assertEquals("/v1/contrib/needs", req.url.encodedPath)
        assertEquals("movie", req.url.queryParameter("media_type"))
        assertEquals("tt0133093", req.url.queryParameter("imdb_id"))
        assertEquals("603", req.url.queryParameter("tmdb_id"))
        assertEquals("25000", req.url.queryParameter("duration_ms"))
        assertEquals(fp, req.url.queryParameter("fp"))
        assertNull(req.url.queryParameter("app_version"))
        assertEquals("sk_live_x", req.header("X-API-Key"))
        assertNull(req.header("X-Seekr-Installation"))
    }

    @Test
    fun `needs for an episode and for anime`() {
        val c = client { reply(it, 404) }
        runBlocking {
            c.needs(SeekrContent.Episode(showImdbId = "tt0903747", showTmdbId = 1396, season = 1, episode = 3), 25_000, fp)
            c.needs(SeekrContent.Anime(1376, 5), 25_000, fp)
            c.needs(SeekrContent.Anime(1376), 25_000, fp)
        }
        val (ep, anime, animeMovie) = seen.map { it.request.url }
        assertEquals("episode", ep.queryParameter("media_type"))
        assertEquals("tt0903747", ep.queryParameter("show_imdb_id"))
        assertEquals("1396", ep.queryParameter("show_tmdb_id"))
        assertEquals("1", ep.queryParameter("season"))
        assertEquals("3", ep.queryParameter("episode"))
        assertNull(ep.queryParameter("imdb_id"))
        assertEquals("anime_episode", anime.queryParameter("media_type"))
        assertEquals("1376", anime.queryParameter("kitsu_id"))
        assertEquals("5", anime.queryParameter("episode"))
        assertEquals("anime_movie", animeMovie.queryParameter("media_type"))
        assertEquals("1376", animeMovie.queryParameter("kitsu_id"))
        assertNull(animeMovie.queryParameter("episode"))
    }

    @Test
    fun `needs with a bearer sends Authorization and no key`() {
        val c = client(SeekrAuth.Bearer { "tvtok" }) { reply(it, 404) }
        runBlocking { c.needs(movie, 25_000, fp) }
        assertEquals("Bearer tvtok", seen.single().request.header("Authorization"))
        assertNull(seen.single().request.header("X-API-Key"))
    }

    @Test
    fun `a signed-out bearer and unsupported content never touch the network`() {
        val signedOut = client(SeekrAuth.Bearer { null }) { error("no request expected") }
        assertEquals(NeedsResult.Failure(ContributionError.SignedOut), runBlocking { signedOut.needs(movie, 25_000, fp) })
        assertEquals(SubmitResult.Failure(ContributionError.SignedOut), submit(signedOut))
        assertEquals(StatusResult.Failure(ContributionError.SignedOut), runBlocking { signedOut.status("x") })

        val c = client { error("no request expected") }
        val unsupported = ContributionError.UnsupportedContent
        assertEquals(NeedsResult.Failure(unsupported), runBlocking { c.needs(SeekrContent.Movie(tmdbId = 603), 25_000, fp) })
        assertEquals(NeedsResult.Failure(unsupported), runBlocking { c.needs(SeekrContent.Episode(showTmdbId = 1, season = 1, episode = 1), 25_000, fp) })
        assertEquals(SubmitResult.Failure(unsupported), submit(c, SeekrContent.Movie(tmdbId = 603)))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `needs decodes both bitmaps against n_slots and capture is their union`() {
        val c = client { reply(it, 200, """{"generate":true,"reason":"ok","grid_ms":10000,"n_slots":40,"job_duration_ms":400000,"needed":"AAAAwP8","overlap":"4AEECAA","upload_after_cues":30,"extra":1}""") }
        val needs = (runBlocking { c.needs(movie, 400_000, fp) } as NeedsResult.Success).needs
        assertTrue(needs.generate)
        assertEquals(NeedsReason.Ok, needs.reason)
        assertEquals(10_000, needs.gridMs)
        assertEquals(40, needs.nSlots)
        assertEquals(400_000L, needs.jobDurationMs)
        assertEquals(30, needs.uploadAfterCues)
        assertEquals((30..39).toList(), needs.needed.toList())
        assertEquals(listOf(5, 6, 7, 8, 18, 27), needs.overlap.toList())
        assertEquals(16, needs.capture.size)
    }

    @Test
    fun `needs reasons map and non-ok means empty sets and no capture`() {
        val reasons = mapOf(
            "have_exact" to NeedsReason.HaveExact, "not_observed" to NeedsReason.NotObserved, "ignored" to NeedsReason.Ignored,
            "blocked_takedown" to NeedsReason.BlockedTakedown, "job_closed" to NeedsReason.JobClosed, "job_cap" to NeedsReason.JobCap,
            "something_new" to NeedsReason.Unknown,
        )
        for ((wire, reason) in reasons) {
            // a hostile/new server may still send a flag and bitmaps: neither is honoured
            val c = client { reply(it, 200, """{"generate":true,"reason":"$wire","grid_ms":10000,"n_slots":40,"job_duration_ms":400000,"needed":"AAAAwP8","overlap":"","upload_after_cues":30}""") }
            val needs = (runBlocking { c.needs(movie, 400_000, fp) } as NeedsResult.Success).needs
            assertEquals(reason, needs.reason, wire)
            assertFalse(needs.generate, wire)
            assertTrue(needs.needed.isEmpty && needs.overlap.isEmpty, wire)
            assertEquals(40, needs.needed.nSlots)
        }
    }

    @Test
    fun `ok with nothing needed is generate false`() {
        val c = client { reply(it, 200, """{"generate":false,"reason":"ok","grid_ms":10000,"n_slots":12,"job_duration_ms":120000,"needed":"AAA","overlap":"","upload_after_cues":30}""") }
        val needs = (runBlocking { c.needs(movie, 120_000, fp) } as NeedsResult.Success).needs
        assertFalse(needs.generate)
        assertEquals(NeedsReason.Ok, needs.reason)
        assertTrue(needs.capture.isEmpty)
    }

    @Test
    fun `needs failures`() {
        fun needs(code: Int, body: String, vararg h: Pair<String, String>) =
            (runBlocking { client { reply(it, code, body, *h) }.needs(movie, 25_000, fp) } as NeedsResult.Failure).error
        assertEquals(ContributionError.RateLimited(1800), needs(429, """{"error":"quota_exceeded"}""", "Retry-After" to "1800"))
        assertEquals(ContributionError.NotAllowed, needs(403, """{"error":"contrib_not_allowed"}"""))
        assertEquals(ContributionError.Unauthorized, needs(401, """{"error":"unauthorized"}"""))
        assertEquals(ContributionError.Rejected(400, "invalid_envelope", null), needs(400, """{"error":"invalid_envelope"}"""))
        // a 200 that is not the contract's shape, or a bitmap of the wrong length: unparseable
        assertEquals(ContributionError.Network, needs(200, "<html>"))
        assertEquals(ContributionError.Network, needs(200, """{"generate":true,"reason":"ok","n_slots":40,"needed":"AA","overlap":""}"""))
    }

    // ---- submit ----

    @Test
    fun `an api-key submit mints an installation once, persists it and sends the package raw`() {
        val store = InMemoryInstallationStore()
        val c = client(store = store, answer = submitting { reply(it, 201, submissionBody) })
        val result = submit(c)
        assertEquals(2, seen.size)
        val mint = seen[0]
        assertEquals("POST", mint.request.method)
        assertEquals("/v1/contrib/installations", mint.request.url.encodedPath)
        assertEquals("sk_live_x", mint.request.header("X-API-Key"))

        val put = seen[1]
        assertEquals("PUT", put.request.method)
        assertEquals("/v1/contrib/submissions", put.request.url.encodedPath)
        assertContentEquals(nvmt, put.body)
        assertEquals("application/vnd.seekr.nvmt", put.request.body!!.contentType().toString())
        assertEquals(nvmt.size.toLong(), put.request.body!!.contentLength())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(nvmt).joinToString("") { "%02x".format(it.toInt() and 0xFF) }, put.request.header("X-Content-SHA256"))
        assertEquals("ci1_token", put.request.header("X-Seekr-Installation"))
        assertEquals("sk_live_x", put.request.header("X-API-Key"))
        val q = put.request.url
        assertEquals("movie", q.queryParameter("media_type"))
        assertEquals("tt0133093", q.queryParameter("imdb_id"))
        assertEquals("25000", q.queryParameter("duration_ms"))
        assertEquals(fp, q.queryParameter("fp"))
        assertEquals("seekr-tv/1.4.0", q.queryParameter("app_version"))
        assertNull(q.queryParameter("release_hint_hash"))
        assertNull(q.queryParameter("dry_run"))

        val s = (result as SubmitResult.Accepted).submission
        assertEquals("0b0d1f9e-1111-2222-3333-444444444444", s.id)
        assertEquals("pending", s.status)
        assertEquals(JobStatus.Collecting, s.jobStatus)
        assertFalse(s.duplicate)
        assertEquals(listOf(1), s.needed!!.toList()) // "Ag" = byte 02 = cue 1 of 3 slots
        assertEquals(3, s.needed!!.nSlots)
        assertNull(s.rejectCode)
        assertEquals("ci1_token", runBlocking { store.load() })

        // the second submission reuses the stored token
        submit(c)
        assertEquals(3, seen.size)
        assertEquals("PUT", seen[2].request.method)
        assertEquals("ci1_token", seen[2].request.header("X-Seekr-Installation"))
    }

    @Test
    fun `a stored token is used without minting`() {
        val store = InMemoryInstallationStore().also { runBlocking { it.save("ci1_old") } }
        val c = client(store = store) { reply(it, 201, submissionBody) }
        submit(c)
        assertEquals(1, seen.size)
        assertEquals("ci1_old", seen[0].request.header("X-Seekr-Installation"))
    }

    @Test
    fun `release hint hash and dry run are sent when given`() {
        val c = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) {
            reply(it, 200, """{"ok":true,"observed":true,"grid_ms":10000,"n_slots":3,"tile_count":2}""")
        }
        val hash = "ab".repeat(32)
        val result = runBlocking { c.submit(movie, 25_000, fp, nvmt, "seekr-tv/1.4.0", releaseHintHash = hash, dryRun = true) }
        assertEquals(hash, seen.single().request.url.queryParameter("release_hint_hash"))
        assertEquals("1", seen.single().request.url.queryParameter("dry_run"))
        assertEquals(SubmitResult.DryRun(DryRunInfo(observed = true, gridMs = 10_000, nSlots = 3, tileCount = 2)), result)
    }

    @Test
    fun `anime submissions carry kitsu id and episode`() {
        val c = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) { reply(it, 201, submissionBody) }
        submit(c, SeekrContent.Anime(1376, 1))
        val q = seen.single().request.url
        assertEquals("anime_episode", q.queryParameter("media_type"))
        assertEquals("1376", q.queryParameter("kitsu_id"))
        assertEquals("1", q.queryParameter("episode"))
    }

    @Test
    fun `a duplicate is accepted with the flag and an undecodable needed is dropped`() {
        val c = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) {
            reply(it, 200, """{"id":"i","status":"promoted","job_status":"promoted","duplicate":true,"needed":"AAAAAAAAAAAA","reject_code":null}""")
        }
        val s = (submit(c) as SubmitResult.Accepted).submission
        assertTrue(s.duplicate)
        assertEquals(JobStatus.Promoted, s.jobStatus)
        assertNull(s.needed)
    }

    @Test
    fun `job status strings map, null stays null and a new one is Unknown`() {
        fun statusOf(js: String): JobStatus? {
            val c = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) {
                reply(it, 201, """{"id":"i","status":"pending","job_status":$js}""")
            }
            return (submit(c) as SubmitResult.Accepted).submission.jobStatus
        }
        assertEquals(JobStatus.AwaitingApproval, statusOf("\"awaiting_approval\""))
        assertEquals(JobStatus.Approved, statusOf("\"approved\""))
        assertEquals(JobStatus.Rejected, statusOf("\"rejected\""))
        assertEquals(JobStatus.Expired, statusOf("\"expired\""))
        assertEquals(JobStatus.TakenDown, statusOf("\"taken_down\""))
        assertEquals(JobStatus.Unknown, statusOf("\"archived\""))
        assertNull(statusOf("null"))
    }

    @Test
    fun `a bearer submit sends no installation and never mints`() {
        val c = client(SeekrAuth.Bearer { "tvtok" }) { reply(it, 201, submissionBody) }
        assertIs<SubmitResult.Accepted>(submit(c))
        val req = seen.single().request
        assertEquals("PUT", req.method)
        assertEquals("Bearer tvtok", req.header("Authorization"))
        assertNull(req.header("X-Seekr-Installation"))
        assertNull(req.header("X-API-Key"))
    }

    @Test
    fun `installations with a bearer is NotAllowed without a request`() {
        val c = client(SeekrAuth.Bearer { "tvtok" }) { error("no request expected") }
        assertEquals(InstallationResult.Failure(ContributionError.NotAllowed), runBlocking { c.installations() })
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `installation_invalid re-mints once and retries once`() {
        val store = InMemoryInstallationStore().also { runBlocking { it.save("ci1_stale") } }
        var minted = 0
        val c = client(store = store) { req ->
            when {
                req.url.encodedPath == "/v1/contrib/installations" -> {
                    minted++
                    reply(req, 201, """{"installation":"ci1_fresh","usable_after":"2026-11-15T10:00:00Z"}""")
                }
                req.header("X-Seekr-Installation") == "ci1_stale" -> reply(req, 401, """{"error":"installation_invalid"}""")
                else -> reply(req, 201, submissionBody)
            }
        }
        assertIs<SubmitResult.Accepted>(submit(c))
        assertEquals(1, minted)
        assertEquals(listOf("PUT", "POST", "PUT"), seen.map { it.request.method })
        assertEquals("ci1_fresh", seen[2].request.header("X-Seekr-Installation"))
        assertEquals("ci1_fresh", runBlocking { store.load() })
    }

    @Test
    fun `a second installation_invalid is Unauthorized, not a loop`() {
        var puts = 0
        val c = client(answer = submitting {
            puts++
            reply(it, 401, """{"error":"installation_invalid"}""")
        })
        assertEquals(ContributionError.Unauthorized, error(submit(c)))
        assertEquals(2, puts)
    }

    @Test
    fun `a plain 401 does not re-mint`() {
        val c = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) { reply(it, 401, """{"error":"unauthorized"}""") }
        assertEquals(ContributionError.Unauthorized, error(submit(c)))
        assertEquals(1, seen.size)
    }

    @Test
    fun `a failed mint is surfaced and the submission is not attempted`() {
        val c = client { req ->
            if (req.url.encodedPath == "/v1/contrib/installations") reply(req, 429, """{"error":"quota_exceeded"}""", "Retry-After" to "3600") else error("no PUT expected")
        }
        assertEquals(ContributionError.RateLimited(3600), error(submit(c)))
    }

    // ---- error mapping ----

    private fun mapped(code: Int, body: String, vararg h: Pair<String, String>): ContributionError {
        seen.clear()
        val c = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) { reply(it, code, body, *h) }
        return error(submit(c))
    }

    @Test
    fun `every documented error code maps`() {
        assertEquals(ContributionError.Unauthorized, mapped(401, """{"error":"unauthorized"}"""))
        assertEquals(ContributionError.NotEnabled, mapped(403, """{"error":"contrib_not_enabled"}"""))
        assertEquals(ContributionError.NotAllowed, mapped(403, """{"error":"contrib_not_allowed"}"""))
        assertEquals(ContributionError.InstallationTooNew, mapped(403, """{"error":"installation_too_new"}"""))
        assertEquals(ContributionError.Suspended, mapped(403, """{"error":"contrib_blocked"}"""))
        assertEquals(ContributionError.Suspended, mapped(403, """{"error":"account suspended"}"""))
        assertEquals(ContributionError.Rejected(403, "forbidden", null), mapped(403, """{"error":"forbidden"}"""))
        assertEquals(ContributionError.NotObserved, mapped(409, """{"error":"not_observed"}"""))
        assertEquals(ContributionError.JobClosed("have_exact"), mapped(409, """{"error":"job_closed","detail":"have_exact"}"""))
        assertEquals(ContributionError.JobClosed(null), mapped(409, """{"error":"job_closed"}"""))
        assertEquals(ContributionError.JobCap("tiles"), mapped(409, """{"error":"job_cap","detail":"tiles"}"""))
        assertEquals(ContributionError.JobMismatch(360, 3_600_000), mapped(409, """{"error":"job_mismatch","n_slots":360,"job_duration_ms":3600000}"""))
        assertEquals(ContributionError.Rejected(409, "job_mismatch", null), mapped(409, """{"error":"job_mismatch"}"""))
        assertEquals(ContributionError.Rejected(409, "weird", null), mapped(409, """{"error":"weird"}"""))
        assertEquals(ContributionError.RateLimited(120), mapped(429, """{"error":"rate_limited"}""", "Retry-After" to "120"))
        assertEquals(ContributionError.RateLimited(null), mapped(429, """{"error":"quota_exceeded"}"""))
        assertEquals(ContributionError.RateLimited(null), mapped(429, """{"error":"rate_limited"}""", "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(ContributionError.Disabled, mapped(503, """{"error":"contrib_disabled"}"""))
        assertEquals(ContributionError.ServerBusy(30), mapped(503, """{"error":"server_busy"}""", "Retry-After" to "30"))
        assertEquals(ContributionError.Server(503), mapped(503, "<html>bad gateway</html>"))
        assertEquals(ContributionError.Server(500), mapped(500, ""))
        assertEquals(ContributionError.Server(502), mapped(502, "oops"))
    }

    @Test
    fun `other 4xx are Rejected with the code and detail`() {
        assertEquals(ContributionError.Rejected(400, "invalid_envelope", null), mapped(400, """{"error":"invalid_envelope"}"""))
        assertEquals(ContributionError.Rejected(400, "checksum_mismatch", null), mapped(400, """{"error":"checksum_mismatch"}"""))
        assertEquals(ContributionError.Rejected(408, "upload_too_slow", null), mapped(408, """{"error":"upload_too_slow"}"""))
        assertEquals(ContributionError.Rejected(411, "length_required", null), mapped(411, """{"error":"length_required"}"""))
        assertEquals(ContributionError.Rejected(413, "payload_too_large", null), mapped(413, """{"error":"payload_too_large"}"""))
        assertEquals(ContributionError.Rejected(415, "unsupported_media_type", null), mapped(415, """{"error":"unsupported_media_type"}"""))
        assertEquals(ContributionError.Rejected(422, "tile_size", "tile 3: 640x360"), mapped(422, """{"error":"tile_size","detail":"tile 3: 640x360"}"""))
        assertEquals(ContributionError.Rejected(422, "", null), mapped(422, "not json"))
    }

    @Test
    fun `offline and unparseable successes are Network`() {
        val offline = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) { throw IOException("unreachable") }
        assertEquals(ContributionError.Network, error(submit(offline)))
        assertEquals(NeedsResult.Failure(ContributionError.Network), runBlocking { offline.needs(movie, 25_000, fp) })
        assertEquals(StatusResult.Failure(ContributionError.Network), runBlocking { offline.status("x") })
        val garbled = client(store = InMemoryInstallationStore().also { runBlocking { it.save("t") } }) { reply(it, 201, "<html>") }
        assertEquals(ContributionError.Network, error(submit(garbled)))
        val mintOffline = client { throw IOException("unreachable") }
        assertEquals(InstallationResult.Failure(ContributionError.Network), runBlocking { mintOffline.installations() })
    }

    // ---- status / installations ----

    @Test
    fun `status requests the id and parses a submission`() {
        val c = client { reply(it, 200, """{"id":"abc","status":"rejected","reject_code":"blank_tiles","job_status":null,"created_at":"x","expires_at":"y"}""") }
        val s = (runBlocking { c.status("abc") } as StatusResult.Success).submission
        assertEquals("/v1/contrib/submissions/abc", seen.single().request.url.encodedPath)
        assertEquals("GET", seen.single().request.method)
        assertEquals("sk_live_x", seen.single().request.header("X-API-Key"))
        assertEquals("rejected", s.status)
        assertEquals("blank_tiles", s.rejectCode)
        assertNull(s.jobStatus)
        assertNull(s.needed)
    }

    @Test
    fun `status escapes the id and maps 404`() {
        val c = client { reply(it, 404, """{"error":"not_found"}""") }
        val failure = runBlocking { c.status("a/b c") } as StatusResult.Failure
        assertEquals("/v1/contrib/submissions/a%2Fb%20c", seen.single().request.url.encodedPath)
        assertEquals(ContributionError.Rejected(404, "not_found", null), failure.error)
    }

    @Test
    fun `installations parses usable_after and persists the token`() {
        val store = InMemoryInstallationStore()
        val c = client(store = store) { reply(it, 201, mintBody) }
        val ok = runBlocking { c.installations() } as InstallationResult.Success
        assertEquals("ci1_token", ok.token)
        // 2026-11-15T10:00:00.250Z
        assertEquals(1_794_736_800_250L, ok.usableAfterEpochMs)
        assertEquals("ci1_token", runBlocking { store.load() })
        assertEquals("POST", seen.single().request.method)
        assertEquals(0L, seen.single().request.body!!.contentLength())
    }

    @Test
    fun `an installation response without a usable token is Network and stores nothing`() {
        val store = InMemoryInstallationStore()
        assertEquals(
            InstallationResult.Failure(ContributionError.Network),
            runBlocking { client(store = store) { reply(it, 201, """{"installation":"t","usable_after":"soon"}""") }.installations() },
        )
        assertNull(runBlocking { store.load() })
    }

    @Test
    fun `nSlotsFor is ceil of durationMs over 10 s`() {
        assertEquals(1, ContributionClient.nSlotsFor(1))
        assertEquals(1, ContributionClient.nSlotsFor(10_000))
        assertEquals(2, ContributionClient.nSlotsFor(10_001))
        assertEquals(360, ContributionClient.nSlotsFor(3_600_000))
        assertEquals(48, ContributionClient.nSlotsFor(479_500))
    }
}
