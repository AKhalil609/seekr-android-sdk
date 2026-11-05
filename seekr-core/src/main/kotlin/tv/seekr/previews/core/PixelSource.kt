package tv.seekr.previews.core

/**
 * Read-only access to the pixels of one decoded video frame.
 *
 * This exists so the matching logic in **seekr-core** stays free of `android.graphics` and
 * can be unit-tested on the JVM. The **seekr-android** artifact provides an adapter for
 * `android.graphics.Bitmap`; if you are on another platform (or writing a test), implement
 * this over whatever pixel buffer you already have.
 *
 * Pixels are packed `0xAARRGGBB` — the layout `Bitmap.getPixels` produces. **Alpha is
 * ignored**: sprite tiles are opaque JPEG, and a frame decoded from a video is opaque too,
 * so there is nothing meaningful to composite against.
 */
interface PixelSource {
    /** Width in pixels. Must be > 0. */
    val width: Int

    /** Height in pixels. Must be > 0. */
    val height: Int

    /**
     * Copies row [y] into [into] as packed `0xAARRGGBB`, one entry per column.
     *
     * Row-at-a-time rather than pixel-at-a-time because the Android implementation is a
     * `Bitmap.getPixels` call per row: a per-pixel accessor would turn one bulk copy into
     * tens of thousands of JNI crossings.
     *
     * @param into a buffer of at least [width] entries. The caller reuses it across rows,
     *   so write exactly `[0, width)` and leave the rest alone.
     */
    fun readRow(y: Int, into: IntArray)

    companion object {
        /**
         * Wraps an existing `0xAARRGGBB` array laid out row-major, [width] entries per row.
         * The array is **not** copied — do not mutate it while a match is in flight.
         */
        fun ofArgb(width: Int, height: Int, pixels: IntArray): PixelSource {
            require(width > 0 && height > 0) { "PixelSource must be non-empty, got ${width}x$height" }
            require(pixels.size >= width * height) {
                "pixels holds ${pixels.size} entries, need ${width * height} for ${width}x$height"
            }
            return ArgbPixelSource(width, height, pixels)
        }
    }
}

private class ArgbPixelSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : PixelSource {
    override fun readRow(y: Int, into: IntArray) {
        pixels.copyInto(into, 0, y * width, y * width + width)
    }
}
