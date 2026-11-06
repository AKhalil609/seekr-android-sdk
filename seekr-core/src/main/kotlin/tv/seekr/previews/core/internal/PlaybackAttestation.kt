package tv.seekr.previews.core.internal

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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

    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

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
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(DOMAIN.toByteArray(Charsets.UTF_8))
        return base64Url(payload + mac.doFinal(payload))
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
    }

    private fun normalizedImdb(id: String?): String = id.orEmpty().trim().lowercase()

    // java.util.Base64 needs API 26; this library supports API 21.
    private fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8) or (bytes[i + 2].toInt() and 0xFF)
            out.append(URL_SAFE[n shr 18 and 63]).append(URL_SAFE[n shr 12 and 63]).append(URL_SAFE[n shr 6 and 63]).append(URL_SAFE[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                out.append(URL_SAFE[n shr 18 and 63]).append(URL_SAFE[n shr 12 and 63])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                out.append(URL_SAFE[n shr 18 and 63]).append(URL_SAFE[n shr 12 and 63]).append(URL_SAFE[n shr 6 and 63])
            }
        }
        return out.toString()
    }
}
