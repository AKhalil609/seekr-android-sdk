package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import tv.seekr.previews.core.SeekrContent

/**
 * The token must match the backend's `internal/playbackattest` byte for byte. The vectors
 * below were produced by that Go code (`Manager.Mint`, ttl 5 min); the same two live in its
 * own tests, so a change on either side that breaks the wire format fails on both.
 */
class PlaybackAttestationTest {
    private val secret = "0123456789abcdef0123456789abcdef".toByteArray()
    private val at = 1_767_323_045_000L // 2026-01-02T03:04:05Z

    @Test
    fun `movie token equals the backend's`() {
        assertEquals(
            "AQ5tb3ZpZTp0bWRiOjYwMwAAAAAAAKQQAAAAAGlXNaUAAAAAaVc20YKjUkvRlcCBGNjdw-PxIFHP1mT3e4jm2tHqeeQi2oV6",
            PlaybackAttestation.mint(secret, "movie:tmdb:603", 42_000, at),
        )
    }

    @Test
    fun `episode token equals the backend's`() {
        assertEquals(
            "ARplcGlzb2RlOmltZGI6dHQwOTAzNzQ3OjE6MwAAAAAAAAAAAAAAAGlXNaUAAAAAaVc20XXEL_sF3AnJ_9zEXKWnqc_EC3PHeVUjn5gSaaWkqR5O",
            PlaybackAttestation.mint(secret, "episode:imdb:tt0903747:1:3", 0, at),
        )
    }

    @Test
    fun `media key follows the backend's precedence`() {
        assertEquals("movie:imdb:tt0133093", PlaybackAttestation.mediaKey(SeekrContent.Movie(tmdbId = 603, imdbId = " TT0133093 ")))
        assertEquals("movie:tmdb:603", PlaybackAttestation.mediaKey(SeekrContent.Movie(tmdbId = 603)))
        assertEquals("episode:tmdb:1396:1:3", PlaybackAttestation.mediaKey(SeekrContent.Episode(showTmdbId = 1396, showImdbId = "tt0903747", season = 1, episode = 3)))
        assertEquals("episode:imdb:tt0903747:1:3", PlaybackAttestation.mediaKey(SeekrContent.Episode(showImdbId = "TT0903747", season = 1, episode = 3)))
    }
}
