package tv.seekr.previews.android.internal

import android.graphics.Bitmap
import android.os.Build
import tv.seekr.previews.android.BitmapSample
import tv.seekr.previews.core.PixelSource
import tv.seekr.previews.core.PreviewFrame
import tv.seekr.previews.core.Sample

/**
 * Reads an Android [Bitmap] as a [PixelSource] for the framework-agnostic matcher in
 * seekr-core.
 *
 * Rows are pulled with `getPixels` one at a time, on demand: the reduction reads each row
 * exactly once, so materialising a whole 1080p frame as an `IntArray` up front would add
 * 8 MB of peak memory to buy nothing.
 *
 * A `HARDWARE` bitmap has no CPU-readable pixels and `getPixels` throws on it, so one is
 * copied into `ARGB_8888` first. That copy is a real cost, which is why it is conditional —
 * every other config is read in place.
 */
internal class BitmapPixelSource(bitmap: Bitmap) : PixelSource {

    private val readable: Bitmap = readableCopyOf(bitmap)

    override val width: Int = readable.width
    override val height: Int = readable.height

    override fun readRow(y: Int, into: IntArray) {
        readable.getPixels(into, 0, width, 0, y, width, 1)
    }

    private companion object {
        fun readableCopyOf(bitmap: Bitmap): Bitmap {
            val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE
            return if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap else bitmap
        }
    }
}

/** Adapts a burst of Android bitmaps to the core matcher's platform-agnostic [Sample]. */
internal fun BitmapSample.toCoreSample(): Sample = Sample(
    frames.map { PreviewFrame(BitmapPixelSource(it.bitmap), it.playbackMs) },
)
