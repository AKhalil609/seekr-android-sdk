package tv.seekr.previews.android

import okhttp3.OkHttpClient
import tv.seekr.previews.android.internal.SheetCache
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews

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
     * previews or the lookup fails — never throws on a normal "not available" outcome.
     *
     * @param durationMs the media duration, used by the API to align cues to the timeline.
     */
    suspend fun loadTrack(content: SeekrContent, durationMs: Long): SeekrTrack? {
        val track = previews.loadTrack(content, durationMs) ?: return null
        return SeekrTrack(track, sheets)
    }

    /** Verifies the API key. Useful for a clear "invalid key" message in a settings screen;
     *  not required before [loadTrack]. */
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
        ): Seekr = Seekr(
            previews = SeekrPreviews.create(apiKey, baseUrl, httpClient),
            sheets = SheetCache(httpClient, maxCachedSheets),
        )
    }
}
