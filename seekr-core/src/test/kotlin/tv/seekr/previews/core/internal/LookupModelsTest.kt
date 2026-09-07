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

    private val json = Json { ignoreUnknownKeys = true }

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
}
