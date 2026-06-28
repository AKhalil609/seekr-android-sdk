package tv.seekr.previews.android.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches full sprite sheets keyed by URL.
 *
 * A title's previews come from one (or a few) large sheets, so a small count-bounded
 * LRU is plenty: the sheet is fetched and decoded once, then every scrub crops from the
 * in-memory bitmap. A mutex collapses the burst of concurrent [get] calls that a fast
 * scrub triggers into a single download per URL.
 */
internal class SheetCache(
    private val http: OkHttpClient,
    maxSheets: Int,
) {
    private val cache = LruCache<String, Bitmap>(maxSheets.coerceAtLeast(1))
    private val mutex = Mutex()

    suspend fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return mutex.withLock {
            // Re-check inside the lock: a racing caller may have populated it already.
            cache.get(url)?.let { return it }
            val bmp = download(url) ?: return null
            cache.put(url, bmp)
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
