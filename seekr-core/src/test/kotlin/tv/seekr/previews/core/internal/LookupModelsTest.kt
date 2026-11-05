package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * [SpriteLookup] is `internal`, which the Kotlin Gradle plugin makes visible to the `test`
 * source set of the same module by default (test compilation is associated with main), so
 * no test-only factory is needed.
 */
class LookupModelsTest {

    // Mirrors the instance in LookupClient — coerceInputValues included, because that is
    // part of what these tests are checking.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `parses a realistic sprites payload including source_duration_ms`() {
        val body = """
            {
              "vtt_url": "https://sprites.seekr.tv/abc123.vtt?sig=xyz",
              "scale": 1.0,
              "source_duration_ms": 8160000,
              "media_type": "movie",
              "title": "Example Movie",
              "tmdb_id": 603
            }
        """.trimIndent()

        val lookup = json.decodeFromString(SpriteLookup.serializer(), body)

        assertEquals("https://sprites.seekr.tv/abc123.vtt?sig=xyz", lookup.vttUrl)
        assertEquals(1.0, lookup.scale)
        assertEquals(8_160_000L, lookup.sourceDurationMs)
    }

    @Test
    fun `defaults source_duration_ms to 0 when the backend omits it`() {
        val body = """
            {
              "vtt_url": "https://sprites.seekr.tv/abc123.vtt?sig=xyz"
            }
        """.trimIndent()

        val lookup = json.decodeFromString(SpriteLookup.serializer(), body)

        assertEquals("https://sprites.seekr.tv/abc123.vtt?sig=xyz", lookup.vttUrl)
        assertEquals(1.0, lookup.scale)
        assertEquals(0L, lookup.sourceDurationMs)
    }

    @Test
    fun `parses the hash sidecar fields`() {
        val body = """
            {
              "vtt_url": "https://sprites.seekr.tv/1318413/6041000/thumbnails.vtt?exp=1&sig=s",
              "scale": 1.0,
              "source_duration_ms": 6041000,
              "hashes_url": "https://sprites.seekr.tv/1318413/6041000/hashes.bin?exp=1&sig=s",
              "hash_algo": "dhash8",
              "hash_count": 604
            }
        """.trimIndent()

        val lookup = json.decodeFromString(SpriteLookup.serializer(), body)

        assertEquals(
            "https://sprites.seekr.tv/1318413/6041000/hashes.bin?exp=1&sig=s",
            lookup.hashesUrl,
        )
        assertEquals("dhash8", lookup.hashAlgo)
        assertEquals(604, lookup.hashCount)
    }

    @Test
    fun `a version without hashes omits the fields entirely and still parses`() {
        // The backend leaves them out rather than sending null, precisely so a Kotlin client
        // declaring non-nullable fields keeps working. This pins that contract from our side.
        val body = """
            {
              "vtt_url": "https://sprites.seekr.tv/abc123.vtt?sig=xyz",
              "scale": 1.0,
              "source_duration_ms": 8160000
            }
        """.trimIndent()

        val lookup = json.decodeFromString(SpriteLookup.serializer(), body)

        assertEquals("", lookup.hashesUrl)
        assertEquals("", lookup.hashAlgo)
        assertEquals(0, lookup.hashCount)
    }

    @Test
    fun `a null in an optional field degrades to its default instead of failing the lookup`() {
        // Defence in depth. Nothing sends nulls today, but a non-nullable Kotlin field handed
        // one is a hard parse error — and this response is the only source of cue data, so the
        // blast radius would be "previews stop working entirely", not "offset matching is
        // unavailable". coerceInputValues makes surviving it our property rather than the
        // server's promise.
        val body = """
            {
              "vtt_url": "https://sprites.seekr.tv/abc123.vtt?sig=xyz",
              "scale": null,
              "source_duration_ms": null,
              "hashes_url": null,
              "hash_algo": null,
              "hash_count": null
            }
        """.trimIndent()

        val lookup = json.decodeFromString(SpriteLookup.serializer(), body)

        assertEquals("https://sprites.seekr.tv/abc123.vtt?sig=xyz", lookup.vttUrl)
        assertEquals(1.0, lookup.scale)
        assertEquals(0L, lookup.sourceDurationMs)
        assertEquals("", lookup.hashesUrl)
        assertEquals("", lookup.hashAlgo)
        assertEquals(0, lookup.hashCount)
    }
}
