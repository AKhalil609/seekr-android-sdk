package tv.seekr.previews.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tv.seekr.previews.android.internal.StatsRecorder

class StatsRecorderTest {
    @Test
    fun `starts empty`() {
        val s = StatsRecorder(FakeSharedPreferences()).stats.value
        assertEquals(ContributionStats(0, 0, 0, 0, 0, 0, 0, null), s)
    }

    @Test
    fun `counts captures per distinct file and derives minutes from tiles`() {
        val r = StatsRecorder(FakeSharedPreferences())
        repeat(13) { r.tileCaptured(FP) }
        repeat(5) { r.tileCaptured(FP2) }
        val s = r.stats.value
        assertEquals(2, s.titlesCaptured)
        assertEquals(18L, s.tilesCaptured)
        assertEquals(3L, s.minutesCaptured) // 18 * 10 s = 180 s
        assertEquals(0, s.titlesUploaded)
    }

    @Test
    fun `counts only accepted uploads`() {
        val r = StatsRecorder(FakeSharedPreferences())
        r.uploaded(FP, tiles = 30, bytes = 9_000, atMs = 1_234)
        r.uploaded(FP, tiles = 6, bytes = 1_000, atMs = 5_678)
        val s = r.stats.value
        assertEquals(1, s.titlesUploaded)
        assertEquals(36L, s.tilesUploaded)
        assertEquals(6L, s.minutesUploaded)
        assertEquals(10_000L, s.bytesUploaded)
        assertEquals(5_678L, s.lastUploadAtMs)
    }

    @Test
    fun `survives a restart`() {
        val prefs = FakeSharedPreferences()
        StatsRecorder(prefs).apply {
            repeat(7) { tileCaptured(FP) }
            uploaded(FP, 7, 2_100, 99)
        }
        val reloaded = StatsRecorder(prefs).stats.value
        assertEquals(1, reloaded.titlesCaptured)
        assertEquals(1, reloaded.titlesUploaded)
        assertEquals(7L, reloaded.tilesCaptured)
        assertEquals(7L, reloaded.tilesUploaded)
        assertEquals(2_100L, reloaded.bytesUploaded)
        assertEquals(99L, reloaded.lastUploadAtMs)
        // a distinct-file set that came back from disk keeps counting distinct files
        val again = StatsRecorder(prefs).also { it.tileCaptured(FP); it.tileCaptured(FP2) }.stats.value
        assertEquals(2, again.titlesCaptured)
    }

    @Test
    fun `reset clears memory and disk`() {
        val prefs = FakeSharedPreferences()
        val r = StatsRecorder(prefs)
        r.tileCaptured(FP)
        r.uploaded(FP, 1, 10, 5)
        r.reset()
        assertEquals(ContributionStats(0, 0, 0, 0, 0, 0, 0, null), r.stats.value)
        assertEquals(ContributionStats(0, 0, 0, 0, 0, 0, 0, null), StatsRecorder(prefs).stats.value)
        assertNull(StatsRecorder(prefs).stats.value.lastUploadAtMs)
    }
}
