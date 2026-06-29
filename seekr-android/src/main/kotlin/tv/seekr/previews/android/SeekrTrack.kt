package tv.seekr.previews.android

import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tv.seekr.previews.android.internal.SheetCache
import tv.seekr.previews.core.PreviewTrack

/**
 * Android-flavored preview track: returns cropped [Bitmap]s by playback position.
 * Obtain one from [Seekr.loadTrack] and hold it for the playback session.
 */
class SeekrTrack internal constructor(
    private val track: PreviewTrack,
    private val sheets: SheetCache,
) {
    /** True when the title has no previews. */
    val isEmpty: Boolean get() = track.isEmpty

    /**
     * Eagerly downloads and caches all sprite sheets for this track in parallel.
     * Call once after [tv.seekr.previews.android.Seekr.loadTrack] so every subsequent
     * [thumbnailAt] call hits the in-memory cache instead of the network.
     */
    suspend fun prefetchSheets() {
        coroutineScope {
            track.sheetUrls.map { url -> async { sheets.get(url) } }.awaitAll()
        }
    }

    /**
     * The thumbnail for [positionMs] as a cropped [Bitmap], or `null` if unavailable.
     *
     * The full sprite sheet is downloaded and decoded once (then cached), so the first
     * call may touch the network while later calls only crop in memory. Safe to call from
     * the main thread — the body switches to background dispatchers for I/O and pixels.
     *
     * Call this whenever your scrub position changes; throttle to your UI's frame rate if
     * the user is dragging quickly.
     */
    suspend fun thumbnailAt(positionMs: Long): Bitmap? {
        val tile = track.tileAt(positionMs) ?: return null
        val sheet = sheets.get(tile.sheetUrl) ?: return null
        // ponytail: crop runs on the caller's dispatcher (Main) — it's a sub-ms pixel copy
        // for typical thumbnail sizes. Avoids a Dispatchers.Default context switch which
        // adds a suspension point and lets collectLatest cancel before the result lands.
        val tileW = tile.w.takeIf { it > 0 } ?: 320
        val tileH = tile.h.takeIf { it > 0 } ?: 180
        val x = tile.x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val w = tileW.coerceAtMost((sheet.width - x).coerceAtLeast(1))
        val h = tileH.coerceAtMost((sheet.height - y).coerceAtLeast(1))
        return runCatching { Bitmap.createBitmap(sheet, x, y, w, h) }.getOrNull()
    }
}
