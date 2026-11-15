package tv.seekr.previews.core.contrib

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews
import tv.seekr.previews.core.internal.Rfc3339

/**
 * Client of the `/v1/contrib` endpoints (DESIGN-v2, `docs/api/seekr-contrib-v1.yaml`). HTTP and network outcomes never
 * throw: they come back as the `Failure` of each result, so a contribution can never take playback down.
 *
 * With [SeekrAuth.ApiKey] an installation token is minted on first use, persisted in the
 * [InstallationStore] and sent as `X-Seekr-Installation` on submissions; a [SeekrAuth.Bearer] (a linked TV
 * session) needs none.
 */
class ContributionClient private constructor(
    private val auth: SeekrAuth,
    baseUrl: String,
    private val http: OkHttpClient,
    private val store: InstallationStore,
) {
    private val base = baseUrl.trimEnd('/')
    private val mintLock = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** GET /v1/contrib/needs. Never throws on expected failures. */
    suspend fun needs(content: SeekrContent, durationMs: Long, fp: String): NeedsResult {
        val envelope = envelope(content) ?: return NeedsResult.Failure(ContributionError.UnsupportedContent)
        val url = endpoint("v1/contrib/needs").newBuilder().apply {
            envelope.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("duration_ms", durationMs.toString())
            addQueryParameter("fp", fp)
        }.build()
        val request = authenticated(Request.Builder().url(url).get()) ?: return NeedsResult.Failure(ContributionError.SignedOut)
        val reply = execute(request) as? Reply.Http ?: return NeedsResult.Failure(ContributionError.Network)
        if (!reply.ok) return NeedsResult.Failure(error(reply))
        return parse(reply) { NeedsResult.Success(toNeeds(json.decodeFromString(NeedsDto.serializer(), it))) }
            ?: NeedsResult.Failure(ContributionError.Network)
    }

    /**
     * PUT /v1/contrib/submissions. [nvmt] is [NvmtWriter.build]. [dryRun] sends `dry_run=1`. Mints and persists an
     * installation token on first use (API-key auth only) and re-mints once on 401 `installation_invalid`.
     */
    suspend fun submit(
        content: SeekrContent,
        durationMs: Long,
        fp: String,
        nvmt: ByteArray,
        appVersion: String,
        releaseHintHash: String? = null,
        dryRun: Boolean = false,
    ): SubmitResult {
        val envelope = envelope(content) ?: return SubmitResult.Failure(ContributionError.UnsupportedContent)
        val url = endpoint("v1/contrib/submissions").newBuilder().apply {
            envelope.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("duration_ms", durationMs.toString())
            addQueryParameter("fp", fp)
            addQueryParameter("app_version", appVersion)
            releaseHintHash?.let { addQueryParameter("release_hint_hash", it) }
            if (dryRun) addQueryParameter("dry_run", "1")
        }.build()
        val body = nvmt.toRequestBody(NVMT_TYPE)
        val checksum = sha256Hex(nvmt)

        suspend fun send(installation: String?): Reply? {
            val builder = Request.Builder().url(url).put(body).header("X-Content-SHA256", checksum)
            installation?.let { builder.header("X-Seekr-Installation", it) }
            return execute(authenticated(builder) ?: return null)
        }

        var installation: String? = null
        if (auth is SeekrAuth.ApiKey) {
            installation = when (val t = installationToken(stale = null)) {
                is InstallationResult.Success -> t.token
                is InstallationResult.Failure -> return SubmitResult.Failure(t.error)
            }
        }
        var reply = send(installation) ?: return SubmitResult.Failure(ContributionError.SignedOut)
        if (auth is SeekrAuth.ApiKey && reply is Reply.Http && reply.code == 401 && errorBody(reply)?.error == "installation_invalid") {
            // The server forgot this installation (or it was bound to another key): mint once more, retry once.
            installation = when (val t = installationToken(stale = installation)) {
                is InstallationResult.Success -> t.token
                is InstallationResult.Failure -> return SubmitResult.Failure(t.error)
            }
            reply = send(installation) ?: return SubmitResult.Failure(ContributionError.SignedOut)
        }
        val answer = reply as? Reply.Http ?: return SubmitResult.Failure(ContributionError.Network)
        if (!answer.ok) return SubmitResult.Failure(error(answer))
        val parsed: SubmitResult? = if (dryRun) {
            parse(answer) { SubmitResult.DryRun(json.decodeFromString(DryRunDto.serializer(), it).toInfo()) }
        } else {
            parse(answer) { SubmitResult.Accepted(toSubmission(json.decodeFromString(SubmissionDto.serializer(), it), packageSlots(nvmt))) }
        }
        return parsed ?: SubmitResult.Failure(ContributionError.Network)
    }

    /** GET /v1/contrib/submissions/{id}. */
    suspend fun status(id: String): StatusResult {
        val url = endpoint("v1/contrib/submissions").newBuilder().addPathSegment(id).build()
        val request = authenticated(Request.Builder().url(url).get()) ?: return StatusResult.Failure(ContributionError.SignedOut)
        val reply = execute(request) as? Reply.Http ?: return StatusResult.Failure(ContributionError.Network)
        if (!reply.ok) return StatusResult.Failure(error(reply))
        return parse(reply) { StatusResult.Success(toSubmission(json.decodeFromString(SubmissionDto.serializer(), it), null)) }
            ?: StatusResult.Failure(ContributionError.Network)
    }

    /**
     * POST /v1/contrib/installations (API-key auth only; with a Bearer: `Failure(NotAllowed)` and no request).
     * Normally implicit in [submit]. Mints a new token and persists it in the [InstallationStore], replacing any older one.
     */
    suspend fun installations(): InstallationResult = mintLock.withLock { mintAndStore() }

    // ---- installation tokens ----

    /** The stored token, or a freshly minted one. [stale] is a token the server just refused: it is never returned again. */
    private suspend fun installationToken(stale: String?): InstallationResult = mintLock.withLock {
        val stored = store.load()
        // Another caller may have re-minted while this one waited for the lock.
        if (stored != null && stored != stale) return@withLock InstallationResult.Success(stored, 0L)
        if (stale != null) store.clear()
        mintAndStore()
    }

    private suspend fun mintAndStore(): InstallationResult {
        val key = (auth as? SeekrAuth.ApiKey)?.key ?: return InstallationResult.Failure(ContributionError.NotAllowed)
        val request = Request.Builder().url(endpoint("v1/contrib/installations"))
            .post(ByteArray(0).toRequestBody(null))
            .header("X-API-Key", key)
            .build()
        val reply = execute(request) as? Reply.Http ?: return InstallationResult.Failure(ContributionError.Network)
        if (!reply.ok) return InstallationResult.Failure(error(reply))
        val result = parse(reply) {
            val dto = json.decodeFromString(InstallationDto.serializer(), it)
            if (dto.installation.isEmpty()) throw SerializationException("empty installation token")
            val usableAfter = Rfc3339.parseMillis(dto.usableAfter) ?: throw SerializationException("bad usable_after")
            InstallationResult.Success(dto.installation, usableAfter)
        } ?: return InstallationResult.Failure(ContributionError.Network)
        store.save(result.token)
        return result
    }

    // ---- HTTP ----

    private sealed interface Reply {
        class Http(val code: Int, val body: String, val retryAfterSec: Int?) : Reply {
            val ok: Boolean get() = code in 200..299
        }

        data object Failed : Reply
    }

    private fun endpoint(path: String): HttpUrl = "$base/$path".toHttpUrl()

    /** Adds the credential; `null` when a bearer has no token (signed out), in which case nothing may be sent. */
    private suspend fun authenticated(builder: Request.Builder): Request? = when (auth) {
        is SeekrAuth.ApiKey -> builder.header("X-API-Key", auth.key).build()
        is SeekrAuth.Bearer -> auth.token()?.let { builder.header("Authorization", "Bearer $it").build() }
    }

    private suspend fun execute(request: Request): Reply = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                Reply.Http(resp.code, resp.body?.string().orEmpty(), resp.header("Retry-After")?.trim()?.toIntOrNull()?.coerceAtLeast(0))
            }
        } catch (_: IOException) {
            Reply.Failed
        }
    }

    private inline fun <T : Any> parse(reply: Reply.Http, block: (String) -> T): T? =
        try {
            block(reply.body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun errorBody(reply: Reply.Http): ErrorDto? =
        runCatching { json.decodeFromString(ErrorDto.serializer(), reply.body) }.getOrNull()

    private fun error(reply: Reply.Http): ContributionError {
        val body = errorBody(reply)
        val code = body?.error.orEmpty()
        val detail = body?.detail
        return when (reply.code) {
            401 -> ContributionError.Unauthorized
            403 -> when {
                code == "contrib_not_enabled" -> ContributionError.NotEnabled
                code == "contrib_not_allowed" -> ContributionError.NotAllowed
                code == "installation_too_new" -> ContributionError.InstallationTooNew
                code == "contrib_blocked" || code.contains("suspended", ignoreCase = true) -> ContributionError.Suspended
                else -> ContributionError.Rejected(403, code, detail)
            }
            409 -> when (code) {
                "not_observed" -> ContributionError.NotObserved
                "job_closed" -> ContributionError.JobClosed(detail)
                "job_cap" -> ContributionError.JobCap(detail)
                "job_mismatch" ->
                    if (body?.nSlots != null && body.jobDurationMs != null) ContributionError.JobMismatch(body.nSlots, body.jobDurationMs)
                    else ContributionError.Rejected(409, code, detail)
                else -> ContributionError.Rejected(409, code, detail)
            }
            429 -> ContributionError.RateLimited(reply.retryAfterSec)
            503 -> when (code) {
                "contrib_disabled" -> ContributionError.Disabled
                "server_busy" -> ContributionError.ServerBusy(reply.retryAfterSec)
                else -> ContributionError.Server(503)
            }
            in 400..499 -> ContributionError.Rejected(reply.code, code, detail)
            else -> ContributionError.Server(reply.code)
        }
    }

    // ---- envelope ----

    /** The identity query parameters, or `null` when this content cannot be contributed (Movie without imdbId, Episode without showImdbId). */
    private fun envelope(content: SeekrContent): List<Pair<String, String>>? = when (content) {
        is SeekrContent.Movie -> content.imdbId?.let {
            listOfNotNull("media_type" to "movie", "imdb_id" to it, content.tmdbId?.let { id -> "tmdb_id" to id.toString() })
        }
        is SeekrContent.Episode -> content.showImdbId?.let {
            listOfNotNull(
                "media_type" to "episode",
                "show_imdb_id" to it,
                content.showTmdbId?.let { id -> "show_tmdb_id" to id.toString() },
                "season" to content.season.toString(),
                "episode" to content.episode.toString(),
            )
        }
        is SeekrContent.Anime ->
            if (content.episode == null) listOf("media_type" to "anime_movie", "kitsu_id" to content.kitsuId.toString())
            else listOf("media_type" to "anime_episode", "kitsu_id" to content.kitsuId.toString(), "episode" to content.episode.toString())
    }

    // ---- wire models ----

    private fun toNeeds(dto: NeedsDto): Needs {
        val reason = when (dto.reason) {
            "ok" -> NeedsReason.Ok
            "have_exact" -> NeedsReason.HaveExact
            "not_observed" -> NeedsReason.NotObserved
            "ignored" -> NeedsReason.Ignored
            "blocked_takedown" -> NeedsReason.BlockedTakedown
            "job_closed" -> NeedsReason.JobClosed
            "job_cap" -> NeedsReason.JobCap
            else -> NeedsReason.Unknown
        }
        val ok = reason == NeedsReason.Ok
        return Needs(
            // An unrecognised reason is "do not capture", whatever the server's flag says.
            generate = dto.generate && ok,
            reason = reason,
            gridMs = dto.gridMs,
            nSlots = dto.nSlots,
            jobDurationMs = dto.jobDurationMs,
            needed = if (ok) CueSet.decode(dto.needed, dto.nSlots) else CueSet.empty(dto.nSlots),
            overlap = if (ok) CueSet.decode(dto.overlap, dto.nSlots) else CueSet.empty(dto.nSlots),
            uploadAfterCues = dto.uploadAfterCues,
        )
    }

    /**
     * The PUT response's `needed` bitmap carries no `n_slots`: it is the job's, which is the package's header value or
     * within one of it (DESIGN-v2 §4.3). A bitmap that fits none of those is dropped rather than failing a submission
     * the server accepted.
     */
    private fun toSubmission(dto: SubmissionDto, packageSlots: Int?): Submission {
        val needed = dto.needed?.takeIf { packageSlots != null }?.let { bitmap ->
            listOf(packageSlots!!, packageSlots + 1, packageSlots - 1).firstNotNullOfOrNull { n ->
                if (n < 0) null else runCatching { CueSet.decode(bitmap, n) }.getOrNull()
            }
        }
        return Submission(
            id = dto.id,
            status = dto.status,
            jobStatus = dto.jobStatus?.let {
                when (it) {
                    "collecting" -> JobStatus.Collecting
                    "awaiting_approval" -> JobStatus.AwaitingApproval
                    "approved" -> JobStatus.Approved
                    "promoted" -> JobStatus.Promoted
                    "rejected" -> JobStatus.Rejected
                    "expired" -> JobStatus.Expired
                    "taken_down" -> JobStatus.TakenDown
                    else -> JobStatus.Unknown
                }
            },
            duplicate = dto.duplicate,
            needed = needed,
            rejectCode = dto.rejectCode,
        )
    }

    /** `n_slots` from the NVMT header (bytes 12..15, little-endian), `null` for something too short to be a package. */
    private fun packageSlots(nvmt: ByteArray): Int? {
        if (nvmt.size < 20) return null
        val n = (nvmt[12].toLong() and 0xFF) or (nvmt[13].toLong() and 0xFF shl 8) or
            (nvmt[14].toLong() and 0xFF shl 16) or (nvmt[15].toLong() and 0xFF shl 24)
        return n.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    @Serializable
    private class ErrorDto(
        @SerialName("error") val error: String = "",
        @SerialName("detail") val detail: String? = null,
        @SerialName("n_slots") val nSlots: Int? = null,
        @SerialName("job_duration_ms") val jobDurationMs: Long? = null,
    )

    @Serializable
    private class InstallationDto(
        @SerialName("installation") val installation: String = "",
        @SerialName("usable_after") val usableAfter: String = "",
    )

    @Serializable
    private class NeedsDto(
        @SerialName("generate") val generate: Boolean = false,
        @SerialName("reason") val reason: String = "",
        @SerialName("grid_ms") val gridMs: Int = 10_000,
        @SerialName("n_slots") val nSlots: Int = 0,
        @SerialName("job_duration_ms") val jobDurationMs: Long = 0,
        @SerialName("needed") val needed: String = "",
        @SerialName("overlap") val overlap: String = "",
        @SerialName("upload_after_cues") val uploadAfterCues: Int = 30,
    )

    @Serializable
    private class SubmissionDto(
        @SerialName("id") val id: String,
        @SerialName("status") val status: String = "",
        @SerialName("job_status") val jobStatus: String? = null,
        @SerialName("duplicate") val duplicate: Boolean = false,
        @SerialName("needed") val needed: String? = null,
        @SerialName("reject_code") val rejectCode: String? = null,
    )

    @Serializable
    private class DryRunDto(
        @SerialName("observed") val observed: Boolean = false,
        @SerialName("grid_ms") val gridMs: Int = 10_000,
        @SerialName("n_slots") val nSlots: Int = 0,
        @SerialName("tile_count") val tileCount: Int = 0,
    ) {
        fun toInfo() = DryRunInfo(observed, gridMs, nSlots, tileCount)
    }

    companion object {
        private val NVMT_TYPE = "application/vnd.seekr.nvmt".toMediaType()

        fun create(
            auth: SeekrAuth,
            baseUrl: String = SeekrPreviews.DEFAULT_BASE_URL,
            httpClient: OkHttpClient = OkHttpClient(),
            installations: InstallationStore = InMemoryInstallationStore(),
        ): ContributionClient = ContributionClient(auth, baseUrl, httpClient, installations)

        /** `ceil(durationMs / 10 000)`: the number of cues (and NVMT slots) of a file. */
        fun nSlotsFor(durationMs: Long): Int {
            require(durationMs > 0) { "durationMs must be positive" }
            return ((durationMs + 9_999) / 10_000).toInt()
        }

        private fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
