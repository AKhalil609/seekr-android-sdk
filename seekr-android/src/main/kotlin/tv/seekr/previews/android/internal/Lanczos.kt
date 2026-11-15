package tv.seekr.previews.android.internal

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Lanczos-3 resampler on interleaved 8-bit RGB, a port of the arithmetic of seekr-core's `PillowResize`
 * (which is `internal` to that module and so not reachable from here): kernel widened by max(1, in/out) when
 * downscaling, normalised in double, 22-bit fixed point, horizontal pass first and clipped to 8 bits, then the
 * vertical pass; an axis whose size is unchanged is skipped. That is what Zoetrope's `_letterbox` (Pillow) does,
 * so a frame captured at its native size letterboxes to the same pixels up to JPEG.
 */
internal object Lanczos {
    private const val PRECISION_BITS = 22
    private const val SUPPORT = 3.0

    class Raster(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height * 3))

    fun resize(src: Raster, outWidth: Int, outHeight: Int): Raster {
        require(outWidth > 0 && outHeight > 0) { "resize target must be positive" }
        if (src.width == outWidth && src.height == outHeight) return src
        val horizontal = if (src.width != outWidth) horizontal(src, outWidth) else src
        return if (src.height != outHeight) vertical(horizontal, outHeight) else horizontal
    }

    private class Coeffs(val bounds: IntArray, val kernel: IntArray, val ksize: Int)

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
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
            for (x in 0 until xmax) if (weightSum != 0.0) row[x] /= weightSum
            for (x in xmax until ksize) row[x] = 0.0
            for (x in 0 until ksize) {
                val v = row[x]
                kernel[xx * ksize + x] =
                    if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt() else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(bounds, kernel, ksize)
    }

    private fun horizontal(src: Raster, outWidth: Int): Raster {
        val out = Raster(outWidth, src.height)
        val c = coeffs(src.width, outWidth)
        for (yy in 0 until src.height) {
            val srcRow = yy * src.width * 3
            val outRow = yy * outWidth * 3
            for (xx in 0 until outWidth) {
                val xmin = c.bounds[xx * 2]
                val xmax = c.bounds[xx * 2 + 1]
                val k = xx * c.ksize
                for (ch in 0 until 3) {
                    var acc = 1 shl (PRECISION_BITS - 1)
                    for (x in 0 until xmax) acc += (src.data[srcRow + (x + xmin) * 3 + ch].toInt() and 0xFF) * c.kernel[k + x]
                    out.data[outRow + xx * 3 + ch] = clip8(acc)
                }
            }
        }
        return out
    }

    private fun vertical(src: Raster, outHeight: Int): Raster {
        val out = Raster(src.width, outHeight)
        val c = coeffs(src.height, outHeight)
        val stride = src.width * 3
        for (yy in 0 until outHeight) {
            val ymin = c.bounds[yy * 2]
            val ymax = c.bounds[yy * 2 + 1]
            val k = yy * c.ksize
            for (i in 0 until stride) {
                var acc = 1 shl (PRECISION_BITS - 1)
                for (y in 0 until ymax) acc += (src.data[(y + ymin) * stride + i].toInt() and 0xFF) * c.kernel[k + y]
                out.data[yy * stride + i] = clip8(acc)
            }
        }
        return out
    }

    private fun clip8(value: Int): Byte {
        val shifted = value shr PRECISION_BITS
        return when {
            shifted <= 0 -> 0
            shifted >= 255 -> 255.toByte()
            else -> shifted.toByte()
        }
    }

    private fun lanczos(x: Double): Double = if (x >= -SUPPORT && x < SUPPORT) sinc(x) * sinc(x / SUPPORT) else 0.0

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = x * PI
        return sin(px) / px
    }
}
