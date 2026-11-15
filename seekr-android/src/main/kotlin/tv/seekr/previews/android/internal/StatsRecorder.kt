package tv.seekr.previews.android.internal

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.seekr.previews.android.ContributionStats

/**
 * Local contribution statistics, persisted in [prefs] (never sent anywhere). Updated only from real captures and accepted uploads.
 *
 * Distinct files are kept as the first 16 hex digits of their `fp` (a 64-bit prefix is plenty to count a viewer's titles and keeps the stored set
 * small and the hash non-reversible to a file). Minutes are derived from tile counts (one tile per 10 s cue), so they never drift from them.
 */
internal class StatsRecorder(private val prefs: SharedPreferences) {
    private val lock = Any()
    private val capturedFiles = HashSet<String>()
    private val uploadedFiles = HashSet<String>()
    private var tilesCaptured = 0L
    private var tilesUploaded = 0L
    private var bytesUploaded = 0L
    private var lastUploadAtMs = -1L

    private val flow: MutableStateFlow<ContributionStats>
    val stats: StateFlow<ContributionStats> get() = flow

    init {
        capturedFiles += prefs.getStringSet(K_CAPTURED_FILES, null) ?: emptySet()
        uploadedFiles += prefs.getStringSet(K_UPLOADED_FILES, null) ?: emptySet()
        tilesCaptured = prefs.getLong(K_TILES_CAPTURED, 0L)
        tilesUploaded = prefs.getLong(K_TILES_UPLOADED, 0L)
        bytesUploaded = prefs.getLong(K_BYTES_UPLOADED, 0L)
        lastUploadAtMs = prefs.getLong(K_LAST_UPLOAD, -1L)
        flow = MutableStateFlow(snapshot())
    }

    fun tileCaptured(fp: String) = synchronized(lock) {
        capturedFiles += key(fp)
        tilesCaptured++
        save()
    }

    fun uploaded(fp: String, tiles: Int, bytes: Long, atMs: Long) = synchronized(lock) {
        uploadedFiles += key(fp)
        tilesUploaded += tiles
        bytesUploaded += bytes
        lastUploadAtMs = atMs
        save()
    }

    fun reset() = synchronized(lock) {
        capturedFiles.clear()
        uploadedFiles.clear()
        tilesCaptured = 0
        tilesUploaded = 0
        bytesUploaded = 0
        lastUploadAtMs = -1L
        save()
    }

    private fun key(fp: String) = fp.take(16)

    private fun snapshot() = ContributionStats(
        titlesCaptured = capturedFiles.size,
        titlesUploaded = uploadedFiles.size,
        minutesCaptured = tilesCaptured * CUE_SECONDS / 60,
        minutesUploaded = tilesUploaded * CUE_SECONDS / 60,
        tilesCaptured = tilesCaptured,
        tilesUploaded = tilesUploaded,
        bytesUploaded = bytesUploaded,
        lastUploadAtMs = lastUploadAtMs.takeIf { it >= 0 },
    )

    private fun save() {
        // putStringSet needs fresh copies: SharedPreferences ignores a mutated set it already holds.
        prefs.edit()
            .putStringSet(K_CAPTURED_FILES, HashSet(capturedFiles))
            .putStringSet(K_UPLOADED_FILES, HashSet(uploadedFiles))
            .putLong(K_TILES_CAPTURED, tilesCaptured)
            .putLong(K_TILES_UPLOADED, tilesUploaded)
            .putLong(K_BYTES_UPLOADED, bytesUploaded)
            .putLong(K_LAST_UPLOAD, lastUploadAtMs)
            .apply()
        flow.value = snapshot()
    }

    companion object {
        const val PREFS_NAME = "seekr_contrib_stats"
        private const val CUE_SECONDS = 10L
        private const val K_CAPTURED_FILES = "captured_files"
        private const val K_UPLOADED_FILES = "uploaded_files"
        private const val K_TILES_CAPTURED = "tiles_captured"
        private const val K_TILES_UPLOADED = "tiles_uploaded"
        private const val K_BYTES_UPLOADED = "bytes_uploaded"
        private const val K_LAST_UPLOAD = "last_upload_at_ms"
    }
}
