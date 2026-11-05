package tv.seekr.previews.android

import okhttp3.OkHttpClient
import tv.seekr.previews.android.internal.SheetCache
import tv.seekr.previews.core.RejectReason
import tv.seekr.previews.core.SeekrAttestation
import tv.seekr.previews.core.SeekrAuth
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews
import tv.seekr.previews.core.TrackResult

/**
 * Android entry point for Seekr seek previews.
 *
 * Wraps the framework-agnostic [SeekrPreviews] core and adds sprite-sheet downloading,
 * cropping and caching, so you get ready-to-draw bitmaps. Create one per app (or per
 * player) and reuse it:
 *
 * ```
 * val seekr = Seekr.create(apiKey = "sk_live_…")
 *
 * // when a title starts playing:
 * val track = seekr.loadTrack(
 *     content = SeekrContent.Movie(tmdbId = 603),
 *     durationMs = player.duration,
 * )
 *
 * // on every scrub-position change:
 * val bitmap = track?.thumbnailAt(scrubPositionMs)   // draw it above your scrubber
 * ```
 */
class Seekr private constructor(
    private val previews: SeekrPreviews,
    private val sheets: SheetCache,
) {
    /**
     * Fetches the preview track for [content]. Returns `null` when the title has no
     * previews or the lookup fails — never throws on a normal "not available" outcome. Use
     * [lookupTrack] to learn *why* there is no track.
     *
     * @param durationMs the media duration, used by the API to align cues to the timeline.
     * @param positionMs the player's current position, bound into the playback-attestation token.
     */
    suspend fun loadTrack(content: SeekrContent, durationMs: Long, positionMs: Long = 0L): SeekrTrack? =
        (lookupTrack(content, durationMs, positionMs) as? SeekrTrackResult.Found)?.track

    /** Like [loadTrack], but says why when there is no track: no previews, refused, rate limited, offline. */
    suspend fun lookupTrack(content: SeekrContent, durationMs: Long, positionMs: Long = 0L): SeekrTrackResult =
        when (val result = previews.lookupTrack(content, durationMs, positionMs)) {
            is TrackResult.Found -> SeekrTrackResult.Found(SeekrTrack(result.track, sheets))
            TrackResult.NoPreviews -> SeekrTrackResult.NoPreviews
            is TrackResult.Rejected -> SeekrTrackResult.Rejected(result.reason)
            is TrackResult.RateLimited -> SeekrTrackResult.RateLimited(result.retryAfterSec)
            TrackResult.Unavailable -> SeekrTrackResult.Unavailable
        }

    /** Verifies an API key. Useful for a clear "invalid key" message in a settings screen;
     *  not required before [loadTrack]. With [SeekrAuth.Bearer] it only reports whether a token is
     *  available — [lookupTrack] says whether the server accepts it. */
    suspend fun validateKey(): Boolean = previews.validateKey()

    companion object {
        /**
         * @param apiKey your `sk_live_` key. On native/TV players with no backend you may
         *   embed a per-user key directly — the per-key distinct-title cap bounds the blast
         *   radius if it leaks, and keys are revocable from the dashboard.
         * @param baseUrl override only for staging / self-hosted control planes.
         * @param httpClient share your app's OkHttp instance to reuse its connection pool.
         * @param maxCachedSheets how many decoded sprite sheets to keep in memory at once.
         */
        fun create(
            apiKey: String,
            baseUrl: String = SeekrPreviews.DEFAULT_BASE_URL,
            httpClient: OkHttpClient = OkHttpClient(),
            maxCachedSheets: Int = 4,
        ): Seekr = create(SeekrAuth.ApiKey(apiKey), baseUrl, httpClient, maxCachedSheets)

        /**
         * @param auth an API key or a signed-in Seekr account's bearer ([SeekrAuth.Bearer]):
         *   an app that signs the user in needs no key at all.
         * @param attestation what signs the playback-attestation token; the default is the
         *   secret this release was built with.
         */
        fun create(
            auth: SeekrAuth,
            baseUrl: String = SeekrPreviews.DEFAULT_BASE_URL,
            httpClient: OkHttpClient = OkHttpClient(),
            maxCachedSheets: Int = 4,
            attestation: SeekrAttestation = SeekrAttestation.BuiltIn,
        ): Seekr = Seekr(
            previews = SeekrPreviews.create(auth, baseUrl, httpClient, attestation),
            sheets = SheetCache(httpClient, maxCachedSheets),
        )
    }
}

/** The outcome of [Seekr.lookupTrack]. */
sealed interface SeekrTrackResult {
    class Found(val track: SeekrTrack) : SeekrTrackResult

    /** The title (or this release of it) has no sprites. Asking again will not help. */
    data object NoPreviews : SeekrTrackResult

    /** The server refused the caller, see [RejectReason]. */
    data class Rejected(val reason: RejectReason) : SeekrTrackResult

    /** Over quota or rate limit; [retryAfterSec] is the server's `Retry-After` when sent. */
    data class RateLimited(val retryAfterSec: Int?) : SeekrTrackResult

    /** Offline, a server error or an unparseable answer. Worth another try later. */
    data object Unavailable : SeekrTrackResult
}
