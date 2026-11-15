package tv.seekr.previews.android

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import tv.seekr.previews.android.internal.Lanczos

/**
 * Letterbox to exactly 320x180 (PACKAGE-SPEC §7.3.3) and JPEG-encode.
 *
 * The geometry is Zoetrope's `fingerprint.normalize`: scale to fit the box keeping the aspect (floor on the scaled sides, at least 1),
 * then centre on black with floor offsets. The resampler is a Lanczos-3 port of Pillow's (see `Lanczos`), so the only difference
 * from Zoetrope is upstream: [SurfaceViewFrameSource] gets the frame from `PixelCopy`, whose own downscale is the GPU's bilinear, not
 * Lanczos (one small copy is what keeps this negligible on Mali-G31 class devices). The dHash reduction downstream is robust to that.
 */
object TileEncoder {
    const val TILE_WIDTH = 320
    const val TILE_HEIGHT = 180
    const val DEFAULT_QUALITY = 80

    private const val MIN_BYTES = 125
    private const val MAX_BYTES = 200_000
    private const val BLACK = 0xFF000000.toInt()

    /** Letterboxes [frame] and encodes it as a baseline JPEG; null when the result is outside 125..200 000 bytes (or the encoder failed). */
    fun encode(frame: CapturedVideoFrame, quality: Int = DEFAULT_QUALITY): ByteArray? {
        val pixels = letterbox(frame.argb, frame.width, frame.height)
        val bitmap = Bitmap.createBitmap(pixels, TILE_WIDTH, TILE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            val out = ByteArrayOutputStream(32 * 1024)
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            if (!ok) null else out.toByteArray().takeIf { it.size in MIN_BYTES..MAX_BYTES }
        } finally {
            bitmap.recycle()
        }
    }

    /** [width] x [height] ARGB to 320*180 opaque ARGB, black bars; alpha of the input is ignored. Pure JVM. */
    fun letterbox(argb: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "frame size must be positive" }
        require(argb.size >= width * height) { "argb is smaller than width x height" }
        val src = Lanczos.Raster(width, height)
        for (i in 0 until width * height) {
            val p = argb[i]
            src.data[i * 3] = (p shr 16).toByte()
            src.data[i * 3 + 1] = (p shr 8).toByte()
            src.data[i * 3 + 2] = p.toByte()
        }
        val newW: Int
        val newH: Int
        val scaled: Lanczos.Raster
        if (width == TILE_WIDTH && height == TILE_HEIGHT) {
            newW = TILE_WIDTH
            newH = TILE_HEIGHT
            scaled = src
        } else {
            val ratio = minOf(TILE_WIDTH.toDouble() / width, TILE_HEIGHT.toDouble() / height)
            newW = maxOf(1, (width * ratio).toInt())
            newH = maxOf(1, (height * ratio).toInt())
            scaled = Lanczos.resize(src, newW, newH)
        }
        val out = IntArray(TILE_WIDTH * TILE_HEIGHT) { BLACK }
        val offsetX = (TILE_WIDTH - newW) / 2
        val offsetY = (TILE_HEIGHT - newH) / 2
        val copyW = minOf(newW, TILE_WIDTH - offsetX)
        val copyH = minOf(newH, TILE_HEIGHT - offsetY)
        for (y in 0 until copyH) {
            for (x in 0 until copyW) {
                val s = (y * newW + x) * 3
                out[(y + offsetY) * TILE_WIDTH + offsetX + x] = BLACK or
                    ((scaled.data[s].toInt() and 0xFF) shl 16) or
                    ((scaled.data[s + 1].toInt() and 0xFF) shl 8) or
                    (scaled.data[s + 2].toInt() and 0xFF)
            }
        }
        return out
    }
}
