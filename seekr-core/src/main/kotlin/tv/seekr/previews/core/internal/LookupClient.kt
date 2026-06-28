package tv.seekr.previews.core.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.seekr.previews.core.SeekrContent

/**
 * Talks to the two Seekr hosts:
 *  - `api.seekr.tv` for the key-gated `/sprites` lookup and `/v1/keys/validate`.
 *  - `sprites.seekr.tv` (whatever host the signed `vtt_url` points at) for the VTT,
 *    which is signature-only — we deliberately send no API key there.
 *
 * Network failures and "no previews" both surface as `null` rather than exceptions,
 * so a missing preview never crashes playback.
 */
internal class LookupClient(
    private val apiKey: String,
    baseUrl: String,
    private val http: OkHttpClient,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCues(content: SeekrContent, durationMs: Long): List<VttCue>? =
        withContext(Dispatchers.IO) {
            val lookup = fetchLookup(content, durationMs) ?: return@withContext null
            val scale = lookup.scale.takeIf { it > 0.0 } ?: 1.0
            val vtt = fetchVtt(lookup.vttUrl) ?: return@withContext null
            Vtt.parse(vtt, scale)
        }

    suspend fun validateKey(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/v1/keys/validate")
            .header("X-API-Key", apiKey)
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

    private fun fetchLookup(content: SeekrContent, durationMs: Long): SpriteLookup? {
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

        val req = Request.Builder().url(url).header("X-API-Key", apiKey).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body == null) return@use null
                json.decodeFromString(SpriteLookup.serializer(), body)
            }
        }.getOrNull()
    }

    // VTT + tiles are signature-only — no API key header here.
    private fun fetchVtt(vttUrl: String): String? {
        val req = Request.Builder().url(vttUrl).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }
}
