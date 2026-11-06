package tv.seekr.previews.android.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads sprite sheets and serves single tiles from them.
 *
 * A Zoetrope sheet is 10x10 tiles of 320x180 = 3200x1800 px, about 23 MB as an ARGB bitmap, and a feature film has
 * eight or more: decoding whole sheets runs a low-RAM TV out of heap once the user has scrubbed across the film.
 * So only the **compressed** bytes of the last [maxSheets] sheets are kept (about 1 MB each), and a tile is decoded
 * on demand with a [BitmapRegionDecoder] into a small byte-bounded LRU. Nothing here decodes a whole sheet.
 */
internal class SheetCache(
    private val http: OkHttpClient,
    private val maxSheets: Int,
    tileCacheBytes: Int = TILE_CACHE_BYTES,
) {
    private class Sheet(
        val decoder: BitmapRegionDecoder,
    )

    // Access-ordered, so the least recently used sheet is the one dropped. Guarded by its own monitor.
    private val sheets =
        object : LinkedHashMap<String, Sheet>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sheet>): Boolean {
                val evict = size > maxSheets.coerceAtLeast(1)
                if (evict) eldest.value.decoder.let { d -> synchronized(d) { d.recycle() } }
                return evict
            }
        }
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val tiles =
        object : LruCache<String, Bitmap>(tileCacheBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    private fun mutexFor(url: String): Mutex = mutexes.computeIfAbsent(url) { Mutex() }

    private fun cachedSheet(url: String): Sheet? = synchronized(sheets) { sheets[url] }

    /**
     * Downloads [url] if it is not held yet (one download per URL, however many callers). A caller cancelled mid-download
     * (the user scrubbed on) still leaves the finished sheet in the cache for the next one.
     */
    suspend fun load(url: String): Boolean {
        if (cachedSheet(url) != null) return true
        return withContext(NonCancellable) {
            mutexFor(url).withLock {
                if (cachedSheet(url) != null) return@withLock true
                val sheet = download(url) ?: return@withLock false
                synchronized(sheets) { sheets[url] = sheet }
                true
            }
        }
    }

    /**
     * The tile at [x], [y] ([w] x [h]) of the sheet at [url]; downloads the sheet when it is not held. Null when the
     * sheet cannot be fetched or decoded.
     */
    suspend fun tile(
        url: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Bitmap? {
        if (!load(url)) return null
        return cachedTile(url, x, y, w, h)
    }

    /** Like [tile] but only from what is already downloaded or decoded: never waits for the network. */
    suspend fun cachedTile(
        url: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Bitmap? {
        val key = "$url#$x,$y,$w,$h"
        tiles.get(key)?.let { return it }
        val sheet = cachedSheet(url) ?: return null
        return withContext(Dispatchers.Default) {
            val decoder = sheet.decoder
            val left = x.coerceIn(0, (decoder.width - 1).coerceAtLeast(0))
            val top = y.coerceIn(0, (decoder.height - 1).coerceAtLeast(0))
            val rect = Rect(left, top, (left + w).coerceAtMost(decoder.width), (top + h).coerceAtMost(decoder.height))
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            // BitmapRegionDecoder is not thread-safe; one tile decode is a few milliseconds.
            val bitmap =
                runCatching {
                    synchronized(decoder) { if (decoder.isRecycled) null else decoder.decodeRegion(rect, opts) }
                }.getOrNull()
            bitmap?.also { tiles.put(key, it) }
        }
    }

    private suspend fun download(url: String): Sheet? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    @Suppress("DEPRECATION")
                    Sheet(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false))
                }
            }.getOrNull()
        }

    private companion object {
        const val TILE_CACHE_BYTES = 6 * 1024 * 1024
        const val INITIAL_CAPACITY = 8
        const val LOAD_FACTOR = 0.75f
    }
}
