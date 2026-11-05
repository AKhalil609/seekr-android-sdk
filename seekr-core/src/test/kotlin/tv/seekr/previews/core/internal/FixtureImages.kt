package tv.seekr.previews.core.internal

import tv.seekr.previews.core.PixelSource

/**
 * The synthetic images the golden fixtures were generated from.
 *
 * Every formula here must stay byte-for-byte equivalent to `pixel()` in
 * `src/test/resources/generate_dhash_fixtures.py`. They are integer-only on purpose:
 * anything involving floating point would risk the two languages disagreeing about the
 * *input* and turning a hash mismatch into a mystery.
 */
internal object FixtureImages {

    fun of(pattern: String, width: Int, height: Int): PixelSource {
        val pixels = IntArray(width * height)
        var at = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[at++] = pixel(pattern, x, y, width, height)
            }
        }
        return PixelSource.ofArgb(width, height, pixels)
    }

    private fun pixel(pattern: String, x: Int, y: Int, w: Int, h: Int): Int = when (pattern) {
        "mosaic" -> argb((x * 7 + y * 13) % 256, (x * x + y * 3) % 256, (x xor y) % 256)
        "gradient" -> argb(
            (x * 255) / maxOf(1, w - 1),
            (y * 255) / maxOf(1, h - 1),
            (x + y) % 256,
        )
        "bars" -> {
            val v = if ((x / 9) % 2 == 0) 255 else 0
            argb(v, v, v)
        }
        "checker" -> {
            val v = if (((x / 5) + (y / 3)) % 2 == 0) 255 else 32
            argb(v, v, v)
        }
        "hifreq" -> argb(
            (x * 131 + y * 197) % 256,
            (x * 37 + y * 241) % 256,
            (x * 89 + y * 53) % 256,
        )
        "flat" -> argb(128, 128, 128)
        else -> error("unknown fixture pattern '$pattern'")
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
