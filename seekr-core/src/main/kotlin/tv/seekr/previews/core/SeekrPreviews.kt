package tv.seekr.previews.core

import okhttp3.OkHttpClient
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
     * which the API uses to align cues to the playback timeline.
     *
     * Returns `null` when the title has no previews or the request fails — this method
     * never throws on a normal "not available" outcome, so it is safe to call optimistically
     * for every title.
     */
    suspend fun loadTrack(content: SeekrContent, durationMs: Long): PreviewTrack? {
        val cues = lookup.loadCues(content, durationMs) ?: return null
        if (cues.isEmpty()) return null
        return PreviewTrack(cues)
    }

    /** Verifies the API key against `/v1/keys/validate`. Use this only to surface a clear
     *  "invalid key" message in settings — it is not required before [loadTrack]. */
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
        ): SeekrPreviews = SeekrPreviews(LookupClient(apiKey, baseUrl, httpClient))
    }
}
