package tv.seekr.previews.android.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches full sprite sheets keyed by URL.
 *
 * ponytail: ConcurrentHashMap (unbounded) instead of LruCache — a session's worth of
 * sprite sheets fits in memory and evicting them causes re-downloads mid-seek. Per-URL
 * mutexes allow different sheets to download in parallel; the old single mutex serialised
 * all fetches and let thumbnailAt() stall behind an unrelated sheet's download.
 * Switch back to LruCache if memory pressure is measured on low-RAM devices.
 */
internal class SheetCache(
    private val http: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") maxSheets: Int,
) {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(url: String): Mutex = mutexes.computeIfAbsent(url) { Mutex() }

    suspend fun get(url: String): Bitmap? {
        cache[url]?.let { return it }
        return mutexFor(url).withLock {
            cache[url]?.let { return it }
            val bmp = download(url) ?: return null
            cache[url] = bmp
            bmp
        }
    }

    private suspend fun download(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }
}
