package tv.seekr.previews.android

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.PixelCopy
import android.view.SurfaceView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PixelCopy of a SurfaceView (API 26+; below that or on failure: null). Reuses its bitmap and a low-priority thread.
 *
 * The copy is at most the requested size, with the surface's own aspect (so the GPU scales, never stretches): one small
 * copy per call. The surface should be sized to the video's aspect (ExoPlayer's `AspectRatioFrameLayout` FIT does that); bars the app
 * draws inside the surface would end up inside the tile. A protected (secure) surface makes `PixelCopy` fail and yields null.
 */
class SurfaceViewFrameSource(private val pts: FramePtsTracker) : FrameSource {
    private val lock = Mutex()
    private var view: SurfaceView? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bitmap: Bitmap? = null

    /** Sets the surface to read; null releases the thread and the bitmap. Call from the main thread. */
    @Synchronized
    fun attach(view: SurfaceView?) {
        this.view = view
        if (view == null) {
            thread?.quitSafely()
            thread = null
            handler = null
            bitmap = null
        } else if (thread == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            thread = HandlerThread("seekr-pixelcopy", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            handler = Handler(thread!!.looper)
        }
    }

    override suspend fun capture(maxWidth: Int, maxHeight: Int): CapturedVideoFrame? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val target: SurfaceView
        val worker: Handler
        synchronized(this) {
            target = view ?: return null
            worker = handler ?: return null
        }
        if (!target.holder.surface.isValid) return null
        val frame = target.holder.surfaceFrame
        if (frame.width() <= 0 || frame.height() <= 0) return null
        val (w, h) = fit(frame.width(), frame.height(), maxWidth, maxHeight)
        return lock.withLock {
            val ptsMs = pts.lastPtsMs // read right before the copy: the frame about to be copied is the last one rendered
            val dest = bitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            val result = withTimeoutOrNull(COPY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Int> { cont ->
                    try {
                        PixelCopy.request(target, dest, { code -> if (cont.isActive) cont.resume(code) }, worker)
                    } catch (e: IllegalArgumentException) {
                        cont.resume(-1) // surface went invalid between the check and the request
                    }
                }
            }
            if (result == null) bitmap = null // a late copy may still write into it: never reuse
            if (result != PixelCopy.SUCCESS) return@withLock null
            val argb = IntArray(w * h)
            dest.getPixels(argb, 0, w, 0, 0, w, h)
            CapturedVideoFrame(w, h, argb, ptsMs)
        }
    }

    internal companion object {
        private const val COPY_TIMEOUT_MS = 1_000L

        /** Largest size inside [maxW] x [maxH] with the aspect of [w] x [h]; never upscales, even sides not required. */
        fun fit(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
            val ratio = minOf(maxW.toDouble() / w, maxH.toDouble() / h, 1.0)
            return maxOf(1, (w * ratio).toInt()) to maxOf(1, (h * ratio).toInt())
        }
    }
}
