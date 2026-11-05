package tv.seekr.previews.core

import okhttp3.OkHttpClient
import tv.seekr.previews.core.internal.AttestationDefaults
import tv.seekr.previews.core.internal.CueLoad
import tv.seekr.previews.core.internal.HashSource
import tv.seekr.previews.core.internal.LookupClient

/**
 * Framework-agnostic Seekr previews client.
 *
 * Resolves a title to a [PreviewTrack] of thumbnail coordinates. Has no Android or
 * image-loading dependencies — depend on the **seekr-android** artifact instead if you
 * want ready-to-draw bitmaps.
 *
 * ```
 * val seekr = SeekrPreviews.create(apiKey = "sk_live_…")
 * val track = seekr.loadTrack(SeekrContent.Movie(tmdbId = 603), durationMs = 8_160_000)
 * val tile = track?.tileAt(positionMs)   // SeekrTile(sheetUrl, x, y, w, h)
 * ```
 *
 * Create one instance and reuse it; it is safe to share across coroutines.
 */
class SeekrPreviews private constructor(
    private val lookup: LookupClient,
) {
    /**
     * Fetches the preview track for [content]. [durationMs] is the media's duration,
     * which the API uses to align cues to the playback timeline. [positionMs] is the
     * player's current position, bound into the playback-attestation token.
     *
     * Returns `null` when the title has no previews or the request fails — this method
     * never throws on a normal "not available" outcome, so it is safe to call optimistically
     * for every title. Use [lookupTrack] to learn *why* there is no track.
     */
    suspend fun loadTrack(content: SeekrContent, durationMs: Long, positionMs: Long = 0L): PreviewTrack? =
        (lookupTrack(content, durationMs, positionMs) as? TrackResult.Found)?.track

    /** Like [loadTrack], but says why when there is no track (no previews, refused, rate limited, offline). */
    suspend fun lookupTrack(content: SeekrContent, durationMs: Long, positionMs: Long = 0L): TrackResult {
        val result = when (val load = lookup.loadCues(content, durationMs, positionMs)) {
            is CueLoad.Loaded -> load.result
            is CueLoad.Failed -> return load.outcome
        }
        if (result.cues.isEmpty()) return TrackResult.NoPreviews
        return TrackResult.Found(
            PreviewTrack(
                cues = result.cues,
                sourceDurationMs = result.sourceDurationMs,
                scale = result.scale,
                // Constructed, not fetched: HashSource downloads nothing until the first
                // matchOffset call, so loading a track costs exactly what it always did.
                hashes = HashSource(
                    hashesUrl = result.hashesUrl,
                    hashAlgo = result.hashAlgo,
                    declaredCount = result.hashCount,
                    expectedCount = result.cues.size,
                    fetcher = { url -> lookup.fetchHashes(url) },
                ),
            ),
        )
    }

    /** Verifies an API key against `/v1/keys/validate`. Use this only to surface a clear
     *  "invalid key" message in settings — it is not required before [loadTrack]. With
     *  [SeekrAuth.Bearer] it only reports whether a token is available; [lookupTrack] says
     *  whether the server accepts it. */
    suspend fun validateKey(): Boolean = lookup.validateKey()

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.seekr.tv/"

        /**
         * @param apiKey your `sk_live_` key.
         * @param baseUrl override only for staging / self-hosted control planes.
         * @param httpClient supply your own OkHttp instance to share connection pools,
         *   timeouts and interceptors with the rest of your app.
         */
        fun create(
            apiKey: String,
            baseUrl: String = DEFAULT_BASE_URL,
            httpClient: OkHttpClient = OkHttpClient(),
        ): SeekrPreviews = create(SeekrAuth.ApiKey(apiKey), baseUrl, httpClient)

        /**
         * @param auth an API key or a signed-in account's bearer, see [SeekrAuth].
         * @param attestation what signs the playback-attestation token, see [SeekrAttestation].
         */
        fun create(
            auth: SeekrAuth,
            baseUrl: String = DEFAULT_BASE_URL,
            httpClient: OkHttpClient = OkHttpClient(),
            attestation: SeekrAttestation = SeekrAttestation.BuiltIn,
        ): SeekrPreviews {
            val secret = when (attestation) {
                SeekrAttestation.BuiltIn -> AttestationDefaults.secret()
                is SeekrAttestation.Secret -> attestation.bytes
                SeekrAttestation.None -> null
            }
            return SeekrPreviews(LookupClient(auth, baseUrl, httpClient, secret))
        }
    }
}
