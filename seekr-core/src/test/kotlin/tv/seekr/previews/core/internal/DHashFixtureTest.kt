package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins [DHash] to the producer that actually generated the hashes we match against.
 *
 * `dhash_fixtures.json` is written by `generate_dhash_fixtures.py` running inside Zoetrope,
 * against the same `zoetrope/fingerprint.py` and the same Pillow the sprite pipeline uses.
 * If this test fails, the client's reduction has drifted from the producer's and **no match
 * will ever succeed** — which in production looks like the matcher quietly declining every
 * time, not like a crash. That silence is why these are exact-equality assertions and not a
 * bit-distance tolerance.
 */
class DHashFixtureTest {

    private data class Fixture(
        val pattern: String,
        val width: Int,
        val height: Int,
        val boxWidth: Int,
        val boxHeight: Int,
        val hash: Long,
    )

    private fun fixtures(): List<Fixture> {
        val text = checkNotNull(javaClass.getResourceAsStream("/dhash_fixtures.json")) {
            "dhash_fixtures.json missing from test resources"
        }.bufferedReader().readText()
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(DHash.ALGO, root["algo"]?.jsonPrimitive?.content, "fixture algo")
        return root.getValue("fixtures").jsonArray.map { element ->
            val o = element.jsonObject
            Fixture(
                pattern = o.getValue("pattern").jsonPrimitive.content,
                width = o.getValue("width").jsonPrimitive.content.toInt(),
                height = o.getValue("height").jsonPrimitive.content.toInt(),
                boxWidth = o.getValue("boxWidth").jsonPrimitive.content.toInt(),
                boxHeight = o.getValue("boxHeight").jsonPrimitive.content.toInt(),
                hash = o.getValue("hash").jsonPrimitive.content.toULong(16).toLong(),
            )
        }
    }

    @Test
    fun `reduction reproduces every Pillow-generated fixture exactly`() {
        val fixtures = fixtures()
        assertTrue(fixtures.size >= 10, "expected a meaningful fixture set, got ${fixtures.size}")
        for (f in fixtures) {
            val image = FixtureImages.of(f.pattern, f.width, f.height)
            val actual = DHash.hash(image, f.boxWidth, f.boxHeight)
            assertEquals(
                f.hash.toULong().toString(16).padStart(16, '0'),
                actual.toULong().toString(16).padStart(16, '0'),
                "${f.pattern} ${f.width}x${f.height} into ${f.boxWidth}x${f.boxHeight}",
            )
        }
    }

    @Test
    fun `skipping the letterbox produces a plausible hash that does not match`() {
        // The failure mode fingerprint.py's docstring warns about, made visible. A 2.35:1
        // frame reduced without being letterboxed into the tile's 16:9 box yields a
        // perfectly reasonable-looking hash — it is simply a hash of a different picture.
        val scope = FixtureImages.of("mosaic", 1280, 536)
        val letterboxed = DHash.hash(scope, 320, 180)
        val unboxed = DHash.hash(scope, 1280, 536) // "box" == the frame, so no padding
        assertTrue(
            DHash.hamming(letterboxed, unboxed) > 8,
            "letterboxing must materially change the hash, got ${DHash.hamming(letterboxed, unboxed)} bits",
        )
    }

    @Test
    fun `a flat field hashes to zero because every neighbour comparison ties`() {
        assertEquals(0L, DHash.hash(FixtureImages.of("flat", 320, 180), 320, 180))
    }

    @Test
    fun `hamming counts differing bits`() {
        assertEquals(0, DHash.hamming(0x0123456789ABCDEFuL.toLong(), 0x0123456789ABCDEFuL.toLong()))
        assertEquals(64, DHash.hamming(0L, -1L))
        assertEquals(1, DHash.hamming(0L, 1L))
    }
}
