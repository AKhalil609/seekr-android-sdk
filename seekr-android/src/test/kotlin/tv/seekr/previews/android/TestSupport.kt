package tv.seekr.previews.android

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import tv.seekr.previews.android.internal.ContributionApi
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.contrib.ContributionError
import tv.seekr.previews.core.contrib.CueSet
import tv.seekr.previews.core.contrib.Needs
import tv.seekr.previews.core.contrib.NeedsReason
import tv.seekr.previews.core.contrib.NeedsResult
import tv.seekr.previews.core.contrib.Submission
import tv.seekr.previews.core.contrib.SubmitResult

internal const val FP = "13c37773434af69a78d3983aae188b6e3b4f5b71c545ba85b64f6da1c9f76393"
internal const val FP2 = "bd0f05dbcacf6c68ddd110722d2a659e0c1001ca4fdf7b2473765ab8e2d75b30"

internal val MOVIE = SeekrContent.Movie(tmdbId = 603, imdbId = "tt0133093")

/** Looks like a JPEG to the NVMT writer (FF D8, 125..200 000 bytes); the contributor never decodes it. */
internal fun fakeJpeg(size: Int = 300): ByteArray = ByteArray(size).also {
    it[0] = 0xFF.toByte()
    it[1] = 0xD8.toByte()
}

internal fun tempDir(): File = Files.createTempDirectory("seekr-contrib-test").toFile().also { it.deleteOnExit() }

internal fun needs(
    nSlots: Int,
    needed: Iterable<Int>,
    overlap: Iterable<Int> = emptyList(),
    generate: Boolean = true,
    reason: NeedsReason = NeedsReason.Ok,
    uploadAfterCues: Int = 30,
) = Needs(
    generate = generate,
    reason = reason,
    gridMs = 10_000,
    nSlots = nSlots,
    jobDurationMs = nSlots * 10_000L,
    needed = CueSet.of(nSlots, needed),
    overlap = CueSet.of(nSlots, overlap),
    uploadAfterCues = uploadAfterCues,
)

internal fun accepted(duplicate: Boolean = false) = SubmitResult.Accepted(
    Submission("id", "pending", null, duplicate, null, null),
)

/** The tiles of an NVMT package as (slot, ptsMs, size); a minimal reader of PACKAGE-SPEC §1. */
internal fun nvmtTiles(nvmt: ByteArray): List<Triple<Int, Long, Int>> {
    fun u32(at: Int) = (0 until 4).sumOf { ((nvmt[at + it].toLong() and 0xFF) shl (8 * it)) }
    val count = u32(16).toInt()
    var pos = 20
    return (0 until count).map {
        val slot = u32(pos).toInt()
        val pts = u32(pos + 4)
        val size = u32(pos + 8).toInt()
        pos += 12 + size
        Triple(slot, pts, size)
    }
}

internal fun nvmtSlots(nvmt: ByteArray): Int = (0 until 4).sumOf { ((nvmt[12 + it].toInt() and 0xFF) shl (8 * it)) }

/** Scripted [ContributionApi]; records what was sent. */
internal class FakeApi : ContributionApi {
    class Submit(val content: SeekrContent, val durationMs: Long, val fp: String, val nvmt: ByteArray) {
        val cues get() = nvmtTiles(nvmt).map { it.first }
    }

    var needsAnswer: () -> NeedsResult = { NeedsResult.Failure(ContributionError.Network) }
    var submitAnswer: (Submit) -> SubmitResult = { accepted() }
    val needsCalls = mutableListOf<String>()
    val submits = mutableListOf<Submit>()

    override suspend fun needs(content: SeekrContent, durationMs: Long, fp: String): NeedsResult {
        needsCalls += fp
        return needsAnswer()
    }

    override suspend fun submit(content: SeekrContent, durationMs: Long, fp: String, nvmt: ByteArray): SubmitResult {
        val s = Submit(content, durationMs, fp, nvmt)
        submits += s
        return submitAnswer(s)
    }
}

/** In-memory [SharedPreferences] that, like the real one, hands out its stored sets read-only in spirit (a copy is returned). */
internal class FakeSharedPreferences(private val store: MutableMap<String, Any?> = HashMap()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = HashMap(store)
    override fun getString(key: String, defValue: String?) = store[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (store[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int) = store[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = store[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = store[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = store[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in store
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) store.clear()
            pending.forEach { (k, v) -> if (v == null) store.remove(k) else store[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
