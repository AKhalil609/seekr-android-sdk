package tv.seekr.previews.core.internal

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * An 8-bit image with [channels] interleaved samples per pixel, row-major.
 *
 * Deliberately a plain byte buffer rather than anything platform-shaped: the whole point of
 * [DHash] is that the reduction runs identically on a JVM unit test and on a device.
 */
internal class Raster(val width: Int, val height: Int, val channels: Int) {
    val data = ByteArray(width * height * channels)

    /** Sample [c] of the pixel at ([x], [y]), as an unsigned 0..255 value. */
    fun sample(x: Int, y: Int, c: Int): Int = data[(y * width + x) * channels + c].toInt() and 0xFF
}

/**
 * A port of Pillow's `ImagingResample` (Lanczos only), faithful down to the fixed-point
 * rounding.
 *
 * ### Why a port and not "a Lanczos resize"
 * The sprite hashes we match against were produced by Pillow — see `zoetrope/fingerprint.py`,
 * whose module docstring pins every step of the reduction and warns that an implementation
 * which merely *looks* right yields plausible hashes that never match. A dHash bit flips
 * whenever two neighbouring 9x8 samples land on opposite sides of a tie, so resampler
 * differences do not average out: they show up directly as Hamming distance against a list
 * that was built with the real thing. Matching a producer means reproducing the producer,
 * not approximating it.
 *
 * Reference: `src/libImaging/Resample.c` in Pillow. The pieces that matter and are easy to
 * get subtly wrong:
 * - `filterscale = max(1, in/out)` — the kernel widens when downscaling but never narrows.
 * - Coefficients are normalised to sum to 1 in `double`, *then* converted to fixed point.
 * - `PRECISION_BITS = 22`, with the accumulator seeded at `1 shl 21` so the final shift
 *   rounds rather than truncates.
 * - Horizontal pass first, clipped to 8 bits, then the vertical pass reads those 8-bit
 *   values. Keeping full precision between passes would be *more* accurate and therefore
 *   wrong.
 * - An axis whose size is unchanged is skipped entirely, exactly as Pillow does.
 */
internal object PillowResize {

    private const val PRECISION_BITS = 22

    /** Lanczos `a`; Pillow's `LANCZOS.support`. */
    private const val SUPPORT = 3.0

    fun resize(src: Raster, outWidth: Int, outHeight: Int): Raster {
        require(outWidth > 0 && outHeight > 0) { "resize target must be positive" }
        if (src.width == outWidth && src.height == outHeight) return src
        val horizontal = if (src.width != outWidth) resizeHorizontal(src, outWidth) else src
        return if (src.height != outHeight) resizeVertical(horizontal, outHeight) else horizontal
    }

    /**
     * Pillow's `precompute_coeffs`. Returns the per-output-pixel source window in [bounds]
     * (pairs of `min, count`) and the fixed-point kernel in [kernel], laid out `ksize` wide.
     */
    private class Coeffs(val bounds: IntArray, val kernel: IntArray, val ksize: Int)

    private fun precomputeCoeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = SUPPORT * filterScale
        val ksize = ceil(support).toInt() * 2 + 1

        val bounds = IntArray(outSize * 2)
        val kernel = IntArray(outSize * ksize)
        val row = DoubleArray(ksize)

        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1.0 / filterScale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin

            var weightSum = 0.0
            for (x in 0 until xmax) {
                val w = lanczos((x + xmin - center + 0.5) * ss)
                row[x] = w
                weightSum += w
            }
            for (x in 0 until xmax) {
                if (weightSum != 0.0) row[x] /= weightSum
            }
            for (x in xmax until ksize) row[x] = 0.0

            // normalize_coeffs_8bpc: round away from zero into fixed point.
            for (x in 0 until ksize) {
                val v = row[x]
                kernel[xx * ksize + x] =
                    if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt()
                    else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(bounds, kernel, ksize)
    }

    private fun resizeHorizontal(src: Raster, outWidth: Int): Raster {
        val out = Raster(outWidth, src.height, src.channels)
        val c = precomputeCoeffs(src.width, outWidth)
        val channels = src.channels
        for (yy in 0 until src.height) {
            val srcRow = yy * src.width * channels
            val outRow = yy * outWidth * channels
            for (xx in 0 until outWidth) {
                val xmin = c.bounds[xx * 2]
                val xmax = c.bounds[xx * 2 + 1]
                val k = xx * c.ksize
                for (ch in 0 until channels) {
                    var acc = 1 shl (PRECISION_BITS - 1)
                    for (x in 0 until xmax) {
                        acc += (src.data[srcRow + (x + xmin) * channels + ch].toInt() and 0xFF) *
                            c.kernel[k + x]
                    }
                    out.data[outRow + xx * channels + ch] = clip8(acc)
                }
            }
        }
        return out
    }

    private fun resizeVertical(src: Raster, outHeight: Int): Raster {
        val out = Raster(src.width, outHeight, src.channels)
        val c = precomputeCoeffs(src.height, outHeight)
        val channels = src.channels
        val stride = src.width * channels
        for (yy in 0 until outHeight) {
            val ymin = c.bounds[yy * 2]
            val ymax = c.bounds[yy * 2 + 1]
            val k = yy * c.ksize
            val outRow = yy * stride
            for (xx in 0 until src.width) {
                for (ch in 0 until channels) {
                    var acc = 1 shl (PRECISION_BITS - 1)
                    for (y in 0 until ymax) {
                        acc += (src.data[(y + ymin) * stride + xx * channels + ch].toInt() and 0xFF) *
                            c.kernel[k + y]
                    }
                    out.data[outRow + xx * channels + ch] = clip8(acc)
                }
            }
        }
        return out
    }

    /**
     * Pillow's `clip8`: arithmetic-shift out the fixed-point fraction, then clamp to a byte.
     * Pillow reaches the same result through a lookup table whose masking is an in-range
     * optimisation, not different behaviour.
     */
    private fun clip8(value: Int): Byte {
        val shifted = value shr PRECISION_BITS
        return when {
            shifted <= 0 -> 0
            shifted >= 255 -> 255.toByte()
            else -> shifted.toByte()
        }
    }

    private fun lanczos(x: Double): Double =
        if (x >= -SUPPORT && x < SUPPORT) sinc(x) * sinc(x / SUPPORT) else 0.0

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = x * PI
        return sin(px) / px
    }
}
