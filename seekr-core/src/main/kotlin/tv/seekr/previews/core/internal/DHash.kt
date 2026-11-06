package tv.seekr.previews.core.internal

import tv.seekr.previews.core.PixelSource

/**
 * The 64-bit perceptual hash the sprite pipeline publishes, reproduced exactly.
 *
 * ### The reduction, and why every step is load-bearing
 * Mirrors `zoetrope/fingerprint.py` (`normalize` then `hash_tile`) step for step:
 *
 * 1. **Letterbox** the frame into the sprite tile's box — decrease-fit preserving aspect
 *    ratio, then centre-pad with black. This matches the generator's ffmpeg
 *    `scale=...:force_original_aspect_ratio=decrease` plus `pad`. It is the step people
 *    skip, and skipping it is undetectable by inspection: the hashes look completely
 *    plausible and never match anything. A 16:9 player frame and a 2.35:1 tile only reduce
 *    to comparable pixels once both sit in the same box with the same bars.
 * 2. **Greyscale** with ITU-R 601-2 luma — Pillow's `convert("L")`, fixed point.
 * 3. **Resize to 9x8** with Lanczos ([PillowResize]).
 * 4. **Compare** each pixel with its right-hand neighbour: 8 comparisons across 9 samples,
 *    8 rows, MSB first, row-major.
 *
 * Note the ordering in (1) and (2): the letterbox resize happens in **colour**, and the
 * greyscale conversion follows it. Greyscaling first would be cheaper and would round
 * differently — which is to say it would produce a different hash from the producer's.
 */
internal object DHash {

    /** The only reduction this SDK understands; matches `fingerprint.ALGO`. */
    const val ALGO = "dhash8"

    /** Tile geometry the generator has used for every version to date. */
    const val DEFAULT_TILE_WIDTH = 320
    const val DEFAULT_TILE_HEIGHT = 180

    // A real scene spans tens of grey levels across its 72 samples; a black or flat frame spans ~0.
    private const val MIN_CONTRAST = 6

    private const val REDUCED_W = 9
    private const val REDUCED_H = 8

    /**
     * Hashes [source] as if it were a tile of [boxWidth] x [boxHeight] in a sprite sheet.
     *
     * [boxWidth]/[boxHeight] must be the *tile's* dimensions from the track's cues, not the
     * frame's own: the frame is being fitted into the tile's geometry so the two reduce the
     * same way.
     */
    fun hash(source: PixelSource, boxWidth: Int, boxHeight: Int): Long =
        reduce(reducedLuma(source, boxWidth, boxHeight))

    private fun reducedLuma(source: PixelSource, boxWidth: Int, boxHeight: Int): Raster {
        val rgb = readRgb(source)
        val boxed = normalize(rgb, boxWidth, boxHeight)
        val grey = toLuma(boxed)
        return PillowResize.resize(grey, REDUCED_W, REDUCED_H)
    }

    private fun contrast(reduced: Raster): Int {
        var lo = 255
        var hi = 0
        for (y in 0 until REDUCED_H) {
            for (x in 0 until REDUCED_W) {
                val v = reduced.sample(x, y, 0)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        return hi - lo
    }

    /** Bit distance between two hashes. 0 is identical, 64 maximally different. */
    /**
     * [hash], or `null` for a frame with no detail to fingerprint — black, a fade, a flat
     * slate. Such a frame hashes to all zeros, which is *also* what flat sprite tiles hash
     * to, so it would match the first one it meets and vote for an offset with confidence.
     * A player's frame grab that returns black (a protected or not-yet-drawn surface) lands
     * here, which is why this guard exists.
     */
    fun hashOrNull(source: PixelSource, boxWidth: Int, boxHeight: Int): Long? {
        val reduced = reducedLuma(source, boxWidth, boxHeight)
        if (contrast(reduced) < MIN_CONTRAST) return null
        return reduce(reduced)
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun readRgb(source: PixelSource): Raster {
        val width = source.width
        val height = source.height
        require(width > 0 && height > 0) { "cannot hash a zero-sized frame (${width}x$height)" }
        val raster = Raster(width, height, 3)
        val row = IntArray(width)
        var at = 0
        for (y in 0 until height) {
            source.readRow(y, row)
            for (x in 0 until width) {
                val argb = row[x]
                raster.data[at++] = (argb ushr 16).toByte()
                raster.data[at++] = (argb ushr 8).toByte()
                raster.data[at++] = argb.toByte()
            }
        }
        return raster
    }

    /**
     * `fingerprint.normalize`: decrease-fit into the box, then centre-paste onto black.
     *
     * The early return for an already-correct size is Pillow's own shortcut, and the
     * `floor` on the scaled dimensions matches ffmpeg's `scale` — off-by-one here shifts
     * every pixel and changes the hash.
     */
    private fun normalize(src: Raster, boxWidth: Int, boxHeight: Int): Raster {
        if (src.width == boxWidth && src.height == boxHeight) return src
        val ratio = minOf(
            boxWidth.toDouble() / src.width,
            boxHeight.toDouble() / src.height,
        )
        val newW = maxOf(1, (src.width * ratio).toInt())
        val newH = maxOf(1, (src.height * ratio).toInt())
        val resized = PillowResize.resize(src, newW, newH)

        val canvas = Raster(boxWidth, boxHeight, 3) // ByteArray is already zero-filled = black
        val offsetX = (boxWidth - newW) / 2
        val offsetY = (boxHeight - newH) / 2
        val copyW = minOf(newW, boxWidth - offsetX)
        val copyH = minOf(newH, boxHeight - offsetY)
        for (y in 0 until copyH) {
            val srcAt = y * newW * 3
            val dstAt = ((y + offsetY) * boxWidth + offsetX) * 3
            resized.data.copyInto(canvas.data, dstAt, srcAt, srcAt + copyW * 3)
        }
        return canvas
    }

    /** Pillow's `rgb2l`: `(R*19595 + G*38470 + B*7471 + 0x8000) shr 16`. */
    private fun toLuma(src: Raster): Raster {
        val out = Raster(src.width, src.height, 1)
        var at = 0
        for (i in out.data.indices) {
            val r = src.data[at].toInt() and 0xFF
            val g = src.data[at + 1].toInt() and 0xFF
            val b = src.data[at + 2].toInt() and 0xFF
            out.data[i] = ((r * 19595 + g * 38470 + b * 7471 + 0x8000) ushr 16).toByte()
            at += 3
        }
        return out
    }

    private fun reduce(reduced: Raster): Long {
        var value = 0L
        for (y in 0 until REDUCED_H) {
            for (x in 0 until REDUCED_H) { // 8 comparisons across 9 samples
                val left = reduced.sample(x, y, 0)
                val right = reduced.sample(x + 1, y, 0)
                value = (value shl 1) or if (left < right) 1L else 0L
            }
        }
        return value
    }
}
