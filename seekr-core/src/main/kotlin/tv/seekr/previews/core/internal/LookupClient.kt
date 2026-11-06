package tv.seekr.previews.core.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.seekr.previews.core.RejectReason
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.TrackResult

/**
 * Result of [LookupClient.loadCues]: the parsed cues plus the `/sprites` lookup metadata
 * needed by [tv.seekr.previews.core.PreviewTrack] (source duration, timebase scale, and the
 * optional perceptual-hash sidecar used for offset matching).
 */
internal data class CueLookupResult(
    val cues: List<VttCue>,
    val sourceDurationMs: Long,
    val scale: Double,
    val hashesUrl: String = "",
    val hashAlgo: String = "",
    val hashCount: Int = 0,
)

/** [LookupClient.loadCues]: the cues, or the [TrackResult] that explains why not. */
internal sealed interface CueLoad {
    class Loaded(val result: CueLookupResult) : CueLoad

    class Failed(val outcome: TrackResult) : CueLoad
}

private sealed interface LookupOutcome {
    class Ok(val lookup: SpriteLookup) : LookupOutcome

    class Failed(val outcome: TrackResult) : LookupOutcome
}

/**
 * Talks to the two Seekr hosts:
 *  - `api.seekr.tv` for the authenticated `/sprites` lookup and `/v1/keys/validate`.
 *  - `sprites.seekr.tv` (whatever host the signed `vtt_url` points at) for the VTT,
 *    which is signature-only — we deliberately send no credentials there.
 *
 * Network failures and "no previews" never throw; they come back as a [TrackResult] so a
 * missing preview can never crash playback.
 */
internal class LookupClient(
    private val auth: SeekrAuth,
    baseUrl: String,
    private val http: OkHttpClient,
    /** Signs `X-Playback-Attestation`; `null` sends none (the server's `log`/`off` modes accept that). */
    private val attestationSecret: ByteArray? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = baseUrl.trimEnd('/')

    // Device clocks on TVs are often wrong, and the attestation token carries an issue/expiry
    // time the server checks against its own. Learned from the Date header of a refusal, then
    // applied to every later token.
    @Volatile
    private var clockSkewMs = 0L

    // ignoreUnknownKeys: the backend adds response fields (title, media_type, tmdb_id, ...)
    // independently of SDK releases. coerceInputValues: a `null` where we declared a
    // non-nullable field with a default is otherwise a hard parse failure, which would take
    // out the whole lookup — no previews at all — rather than just the one field. The
    // backend omits rather than nulls today; this makes surviving it our property instead
    // of the server's promise.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun loadCues(content: SeekrContent, durationMs: Long, positionMs: Long): CueLoad =
        withContext(Dispatchers.IO) {
            val lookup = when (val outcome = fetchLookup(content, durationMs, positionMs)) {
                is LookupOutcome.Ok -> outcome.lookup
                is LookupOutcome.Failed -> return@withContext CueLoad.Failed(outcome.outcome)
            }
            // st=1 asks the sprites Worker to serve cue times already on the client
            // timeline (it applies the scale baked into vtt_url), so no scaling here.
            val vtt = fetchVtt(lookup.vttUrl + "&st=1") ?: return@withContext CueLoad.Failed(TrackResult.Unavailable)
            CueLoad.Loaded(
                CueLookupResult(
                    cues = Vtt.parse(vtt),
                    sourceDurationMs = lookup.sourceDurationMs,
                    scale = lookup.scale,
                    hashesUrl = lookup.hashesUrl,
                    hashAlgo = lookup.hashAlgo,
                    hashCount = lookup.hashCount,
                ),
            )
        }

    /** API keys can be checked against the server; a bearer cannot, so it only reports being signed in. */
    suspend fun validateKey(): Boolean = withContext(Dispatchers.IO) {
        val key = (auth as? SeekrAuth.ApiKey)?.key ?: return@withContext (auth as SeekrAuth.Bearer).token() != null
        val req = Request.Builder()
            .url("$base/v1/keys/validate")
            .header("X-API-Key", key)
            .get()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body == null) return@use false
                json.decodeFromString(KeyValidation.serializer(), body).valid
            }
        }.getOrDefault(false)
    }

    private suspend fun fetchLookup(content: SeekrContent, durationMs: Long, positionMs: Long): LookupOutcome {
        val url = "$base/sprites".toHttpUrl().newBuilder().apply {
            addQueryParameter("duration_ms", durationMs.toString())
            when (content) {
                is SeekrContent.Movie -> {
                    content.imdbId?.let { addQueryParameter("imdb_id", it) }
                    content.tmdbId?.let { addQueryParameter("tmdb_id", it.toString()) }
                }
                is SeekrContent.Episode -> {
                    content.showTmdbId?.let { addQueryParameter("show_tmdb_id", it.toString()) }
                    content.showImdbId?.let { addQueryParameter("show_imdb_id", it) }
                    addQueryParameter("season", content.season.toString())
                    addQueryParameter("episode", content.episode.toString())
                }
            }
        }.build()

        val token = (auth as? SeekrAuth.Bearer)?.token?.invoke()
        if (auth is SeekrAuth.Bearer && token == null) {
            return LookupOutcome.Failed(TrackResult.Rejected(RejectReason.SignedOut))
        }

        // A refusal for attestation whose Date header shows our clock is off is retried once
        // with the corrected clock; any other outcome is final.
        repeat(2) { attempt ->
            val req = Request.Builder().url(url).get().apply {
                when (auth) {
                    is SeekrAuth.ApiKey -> header("X-API-Key", auth.key)
                    is SeekrAuth.Bearer -> header("Authorization", "Bearer $token")
                }
                attestationSecret?.let {
                    val position = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
                    header(
                        "X-Playback-Attestation",
                        PlaybackAttestation.mint(it, PlaybackAttestation.mediaKey(content), position, clock() + clockSkewMs),
                    )
                }
            }.build()
            val outcome = runCatching {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    when {
                        resp.isSuccessful && body != null ->
                            runCatching { LookupOutcome.Ok(json.decodeFromString(SpriteLookup.serializer(), body)) }
                                .getOrElse { LookupOutcome.Failed(TrackResult.Unavailable) }
                        else -> {
                            val failed = classify(resp, body)
                            if (attempt == 0 && failed.outcome == TrackResult.Rejected(RejectReason.Attestation) && learnSkew(resp)) {
                                null
                            } else {
                                failed
                            }
                        }
                    }
                }
            }.getOrElse { LookupOutcome.Failed(TrackResult.Unavailable) }
            if (outcome != null) return outcome
        }
        return LookupOutcome.Failed(TrackResult.Rejected(RejectReason.Attestation))
    }

    private fun classify(resp: Response, body: String?): LookupOutcome.Failed {
        val message = body?.let { runCatching { json.decodeFromString(ErrorBody.serializer(), it).error }.getOrNull() }.orEmpty()
        val outcome: TrackResult = when (resp.code) {
            404 -> TrackResult.NoPreviews
            401 -> TrackResult.Rejected(RejectReason.Unauthorized)
            403 -> TrackResult.Rejected(
                when {
                    message.contains("attestation", ignoreCase = true) -> RejectReason.Attestation
                    message.contains("suspended", ignoreCase = true) -> RejectReason.AccountSuspended
                    else -> RejectReason.Other
                },
            )
            410 -> TrackResult.Rejected(RejectReason.Disabled)
            429 -> TrackResult.RateLimited(resp.header("Retry-After")?.toIntOrNull())
            in 400..499 -> TrackResult.Rejected(RejectReason.Other)
            else -> TrackResult.Unavailable
        }
        return LookupOutcome.Failed(outcome)
    }

    /** Reads the server's clock from the `Date` header; `true` when that moved ours by more than a few seconds. */
    private fun learnSkew(resp: Response): Boolean {
        val server = resp.header("Date")?.let { runCatching { HTTP_DATE.get()!!.parse(it)?.time }.getOrNull() } ?: return false
        val skew = server - clock()
        if (kotlin.math.abs(skew - clockSkewMs) < SKEW_TOLERANCE_MS) return false
        clockSkewMs = skew
        return true
    }

    // VTT + tiles are signature-only — no credentials here.
    private fun fetchVtt(vttUrl: String): String? {
        val req = Request.Builder().url(vttUrl).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Downloads a version's `hashes.bin`. Signature-only like the VTT — the `exp`/`sig` on
     * [hashesUrl] came from signing the whole version directory, so no API key is sent.
     *
     * The return type distinguishes "retrying cannot help" from "retrying might", because
     * the caller has to choose between giving up forever and burning battery. A 4xx means
     * the sidecar is absent or the signature is spent, and neither changes for the life of
     * this URL; a 5xx or a dropped connection is worth another go.
     */
    suspend fun fetchHashes(hashesUrl: String): HashFetch = withContext(Dispatchers.IO) {
        // A URL we cannot even parse will not become parseable on a retry, so it declines
        // terminally rather than burning the retry budget. Everything else, including
        // building the request, still sits inside runCatching: OkHttp throws from url()
        // on inputs toHttpUrlOrNull accepts nothing of, and matching runs on the caller's
        // playback coroutine, where a thrown exception is the one unacceptable outcome.
        if (hashesUrl.toHttpUrlOrNull() == null) return@withContext HashFetch.Missing
        runCatching {
            val req = Request.Builder().url(hashesUrl).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use if (resp.code in 400..499) HashFetch.Missing else HashFetch.Transient
                }
                val body = resp.body ?: return@use HashFetch.Transient
                // The sidecar is 8 bytes per thumbnail — a 4-hour title is under 100 KB. A
                // response orders of magnitude larger is a misrouted request or a redirect to
                // something else entirely, and reading it into memory to find that out would
                // be the actual damage. request() buffers at most the cap before answering,
                // so this holds for a chunked response that declares no length at all.
                if (body.contentLength() > MAX_HASHES_BYTES) return@use HashFetch.Missing
                val source = body.source()
                if (source.request(MAX_HASHES_BYTES + 1)) return@use HashFetch.Missing
                HashFetch.Bytes(source.readByteArray())
            }
        }.getOrElse { HashFetch.Transient }
    }

    private companion object {
        const val SKEW_TOLERANCE_MS = 3_000L

        val HTTP_DATE = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        }

        /** 1M hashes: far beyond any real title, small enough that a stray body cannot hurt. */
        const val MAX_HASHES_BYTES = 8L * 1024 * 1024
    }
}

/** Outcome of [LookupClient.fetchHashes]. See its docs for why the failures are split. */
internal sealed interface HashFetch {
    class Bytes(val data: ByteArray) : HashFetch

    /** The bytes are not there and will not be — a spent signature or an absent sidecar. */
    object Missing : HashFetch

    /** A blip: a 5xx, a dropped connection, an empty body. Worth retrying a bounded number of times. */
    object Transient : HashFetch
}
