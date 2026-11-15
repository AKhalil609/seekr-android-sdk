package tv.seekr.previews.core.internal

import java.nio.ByteBuffer
import tv.seekr.previews.core.SeekrContent

/**
 * The `X-Playback-Attestation` token: short-lived proof that the caller is playing the media
 * it asks sprites for. The layout is the backend's (`internal/playbackattest/token.go`) and
 * must match it byte for byte:
 *
 * ```
 * payload   = version(1) | mediaKeyLen(1) | mediaKey | positionMs(i64 BE) | iat(i64 BE) | exp(i64 BE)
 * signature = HMAC-SHA256("seekr-playback-attestation-v1\0" || payload)
 * token     = base64url-nopad(payload || signature)
 * ```
 *
 * A soft anti-scrape gate, not DRM: the secret ships inside the app.
 */
internal object PlaybackAttestation {
    private const val VERSION: Byte = 1
    private const val DOMAIN = "seekr-playback-attestation-v1\u0000"
    private const val MAX_MEDIA_KEY = 255
    const val TTL_SEC = 300L

    fun mint(secret: ByteArray, mediaKey: String, positionMs: Long, nowMs: Long): String {
        val key = mediaKey.toByteArray(Charsets.UTF_8)
        require(key.isNotEmpty() && key.size <= MAX_MEDIA_KEY) { "media key must be 1..$MAX_MEDIA_KEY bytes" }
        val iat = nowMs / 1000
        val payload = ByteBuffer.allocate(2 + key.size + 24)
            .put(VERSION)
            .put(key.size.toByte())
            .put(key)
            .putLong(positionMs.coerceAtLeast(0))
            .putLong(iat)
            .putLong(iat + TTL_SEC)
            .array()
        // The domain prefix is part of the signed message but not of the token.
        val signature = Hmac.sha256(secret, DOMAIN.toByteArray(Charsets.UTF_8) + payload)
        return Base64Url.encode(payload + signature)
    }

    /**
     * The media selector the token is bound to. It mirrors the backend's `canonicalMediaKey`,
     * including its precedence (show TMDB, show IMDb, then movie IMDb, then movie TMDB).
     */
    fun mediaKey(content: SeekrContent): String = when (content) {
        is SeekrContent.Episode -> content.showTmdbId?.let { "episode:tmdb:$it:${content.season}:${content.episode}" }
            ?: "episode:imdb:${normalizedImdb(content.showImdbId)}:${content.season}:${content.episode}"
        is SeekrContent.Movie -> content.imdbId?.takeIf { it.isNotEmpty() }?.let { "movie:imdb:${normalizedImdb(it)}" }
            ?: "movie:tmdb:${content.tmdbId}"
        // spritevault canonicalMediaKey: `anime:kitsu:<id>[:<episode>]`, the same string as the contribution submission key.
        is SeekrContent.Anime -> content.episode?.let { "anime:kitsu:${content.kitsuId}:$it" } ?: "anime:kitsu:${content.kitsuId}"
    }

    private fun normalizedImdb(id: String?): String = id.orEmpty().trim().lowercase()
}
