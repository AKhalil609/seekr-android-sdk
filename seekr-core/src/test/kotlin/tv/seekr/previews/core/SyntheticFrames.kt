package tv.seekr.previews.core

import java.util.Random

/**
 * Deterministic stand-in footage for the matcher tests.
 *
 * ### Why blocks and not noise
 * The reduction ends at 9x8, so anything high-frequency averages away to a flat field and
 * hashes to zero — see the `hifreq` fixtures in `dhash_fixtures.json`, which do exactly
 * that. Test footage therefore has to vary at the scale the hash can actually see, so each
 * "shot" here is a grid of large blocks whose brightnesses come from the shot index.
 *
 * ### Why fractional shots exist
 * [at] renders a moment *between* two shots. That is the situation the burst rule exists for:
 * sprite thumbnails only exist at cue positions, so a frame decoded at an arbitrary playback
 * moment is usually part-way between two of them and resembles neither exactly. A test that
 * only ever fed exact tiles would prove the matcher can find a frame it was handed, which is
 * not the question.
 */
internal object SyntheticFrames {

    private const val COLS = 9
    private const val ROWS = 8

    /** The tile the sprite sheet holds for cue [index]. */
    fun tile(index: Int, width: Int = 320, height: Int = 180): PixelSource =
        render(cells(index), width, height)

    /**
     * A frame decoded at [previewMs] on the sprite source's timeline: the tile for the cue it
     * falls in, crossfaded towards the next one by how far through the interval it is.
     */
    fun at(previewMs: Long, intervalMs: Long, cueCount: Int, width: Int = 320, height: Int = 180): PixelSource {
        val index = (previewMs / intervalMs).toInt().coerceIn(0, cueCount - 1)
        val next = (index + 1).coerceAtMost(cueCount - 1)
        val t = (previewMs % intervalMs).toDouble() / intervalMs
        val a = cells(index)
        val b = cells(next)
        val blended = IntArray(a.size) { (a[it] * (1 - t) + b[it] * t).toInt().coerceIn(0, 255) }
        return render(blended, width, height)
    }

    /** [tile] with every block nudged, standing in for a different encode of the same frame. */
    fun noisyTile(index: Int, amplitude: Int, width: Int = 320, height: Int = 180): PixelSource {
        val rnd = Random(index.toLong() * 31 + 7)
        val cells = cells(index)
        val noisy = IntArray(cells.size) {
            (cells[it] + rnd.nextInt(2 * amplitude + 1) - amplitude).coerceIn(0, 255)
        }
        return render(noisy, width, height)
    }

    private fun cells(index: Int): IntArray {
        // Seeded per shot so a test can re-render the same shot without holding on to it.
        val rnd = Random(index.toLong() * 6364136223846793005L + 1442695040888963407L)
        return IntArray(COLS * ROWS) { rnd.nextInt(256) }
    }

    private fun render(cells: IntArray, width: Int, height: Int): PixelSource {
        val pixels = IntArray(width * height)
        var at = 0
        for (y in 0 until height) {
            val cy = (y * ROWS / height).coerceAtMost(ROWS - 1)
            for (x in 0 until width) {
                val cx = (x * COLS / width).coerceAtMost(COLS - 1)
                val v = cells[cy * COLS + cx]
                pixels[at++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return PixelSource.ofArgb(width, height, pixels)
    }
}
