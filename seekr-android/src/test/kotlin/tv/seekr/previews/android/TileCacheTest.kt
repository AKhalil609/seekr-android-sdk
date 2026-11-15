package tv.seekr.previews.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tv.seekr.previews.android.internal.FileMeta
import tv.seekr.previews.android.internal.TileCache
import tv.seekr.previews.core.SeekrContent

class TileCacheTest {
    private val day = 24L * 3600 * 1000
    private val meta = FileMeta(MOVIE, 5_400_000)
    private var now = 1_000_000L

    private fun cache(root: File, maxBytes: Long = 20L * 1024 * 1024) = TileCache(root, maxBytes, 30 * day, { now })

    private fun fpOf(i: Int) = i.toString(16).padStart(64, '0')

    @Test
    fun `stores tiles sorted by cue and reads them back`() {
        val c = cache(tempDir())
        c.put(FP, meta, 5, 50_100, fakeJpeg(200))
        c.put(FP, meta, 2, 20_000, fakeJpeg(300))
        assertEquals(listOf(2, 5), c.tiles(FP).map { it.cue })
        assertEquals(listOf(20_000L, 50_100L), c.tiles(FP).map { it.ptsMs })
        assertEquals(300, c.tiles(FP).first().read().size)
        assertEquals(setOf(2, 5), c.cues(FP))
    }

    @Test
    fun `a second tile for the same cue replaces the first`() {
        val c = cache(tempDir())
        c.put(FP, meta, 3, 30_000, fakeJpeg(200))
        c.put(FP, meta, 3, 30_400, fakeJpeg(250))
        assertEquals(1, c.tiles(FP).size)
        assertEquals(30_400L, c.tiles(FP).single().ptsMs)
    }

    @Test
    fun `survives a restart with the content envelope so an upload can resume`() {
        val root = tempDir()
        val movie = SeekrContent.Movie(imdbId = "tt0133093")
        val episode = SeekrContent.Episode(showImdbId = "tt0903747", season = 2, episode = 7)
        val anime = SeekrContent.Anime(kitsuId = 7442, episode = 3)
        cache(root).apply {
            put(FP, FileMeta(movie, 1_000), 1, 10_000, fakeJpeg())
            put(FP2, FileMeta(episode, 2_000), 1, 10_000, fakeJpeg())
            put(fpOf(3), FileMeta(anime, 3_000), 1, 10_000, fakeJpeg())
        }
        val reopened = cache(root)
        assertEquals(FileMeta(movie, 1_000), reopened.meta(FP))
        assertEquals(FileMeta(episode, 2_000), reopened.meta(FP2))
        assertEquals(FileMeta(anime, 3_000), reopened.meta(fpOf(3)))
        assertEquals(setOf(FP, FP2, fpOf(3)), reopened.pendingFiles().toSet())
        assertEquals(setOf(1), reopened.cues(FP))
    }

    @Test
    fun `remove and drop`() {
        val c = cache(tempDir())
        for (cue in 1..4) c.put(FP, meta, cue, cue * 10_000L, fakeJpeg())
        c.remove(FP, listOf(2, 3))
        assertEquals(setOf(1, 4), c.cues(FP))
        c.drop(FP)
        assertEquals(emptySet(), c.cues(FP))
        assertNull(c.meta(FP))
        assertTrue(c.pendingFiles().isEmpty())
    }

    @Test
    fun `the 20 MB cap evicts the least recently used file first`() {
        val cap = 1_500L // tile + meta file of each entry count toward it
        val root = tempDir()
        val c = cache(root, cap)
        for (i in 1..3) {
            now += 1_000
            c.put(fpOf(i), meta, 1, 10_000, fakeJpeg(400)) // 3 entries of ~560 B > 1500 B
        }
        assertEquals(setOf(fpOf(2), fpOf(3)), c.pendingFiles().toSet(), "the oldest file is gone")
        assertTrue(c.totalBytes() <= cap)
        // touching a file makes it recent: file 2 survives the next insert, file 3 goes
        now += 1_000
        c.touch(fpOf(2))
        now += 1_000
        c.put(fpOf(4), meta, 1, 10_000, fakeJpeg(400))
        assertTrue(fpOf(2) in c.pendingFiles())
        assertFalse(fpOf(3) in c.pendingFiles())
    }

    @Test
    fun `the default cap is 20 MB across files`() {
        val c = cache(tempDir())
        val mb = 190_000 // under the 200 000 tile limit
        var i = 0
        while (i < 140) { // ~26 MB offered
            now += 10
            c.put(fpOf(1 + i / 20), meta, i % 20, 10_000L * (i % 20), fakeJpeg(mb))
            i++
        }
        assertTrue(c.totalBytes() <= 20L * 1024 * 1024, "total ${c.totalBytes()}")
        assertTrue(c.pendingFiles().isNotEmpty())
        assertTrue(fpOf(1 + 139 / 20) in c.pendingFiles(), "the file being captured is never evicted")
    }

    @Test
    fun `a single file over the cap drops its oldest cues, never the current ones`() {
        val c = cache(tempDir(), maxBytes = 1_000)
        for (cue in 0 until 6) {
            now += 10
            c.put(FP, meta, cue, cue * 10_000L, fakeJpeg(300))
        }
        val cues = c.cues(FP)
        assertTrue(5 in cues)
        assertTrue(cues.size <= 3, "kept ${cues.size}")
        assertTrue(0 !in cues)
    }

    @Test
    fun `entries expire after 30 days`() {
        val c = cache(tempDir())
        c.put(FP, meta, 1, 10_000, fakeJpeg())
        now += 29 * day
        c.put(FP2, meta, 1, 10_000, fakeJpeg())
        c.purge()
        assertEquals(setOf(FP, FP2), c.pendingFiles().toSet())
        now += 2 * day
        c.purge()
        assertEquals(listOf(FP2), c.pendingFiles(), "FP is 31 days old, FP2 two days")
    }

    @Test
    fun `a stopped file keeps no tiles and is not pending but is remembered across restarts`() {
        val root = tempDir()
        val c = cache(root)
        c.put(FP, meta, 1, 10_000, fakeJpeg())
        c.markStopped(FP, meta)
        assertTrue(c.isStopped(FP))
        assertTrue(c.tiles(FP).isEmpty())
        assertTrue(c.pendingFiles().isEmpty())
        assertTrue(cache(root).isStopped(FP))
    }

    @Test
    fun `a leftover directory without a meta is evicted first and does not crash`() {
        val root = tempDir()
        File(root, fpOf(9)).mkdirs()
        File(File(root, fpOf(9)), "t_1_10000.jpg").writeBytes(fakeJpeg(900))
        File(root, "not-a-fp").mkdirs()
        val c = cache(root, 1_000)
        c.put(FP, meta, 1, 10_000, fakeJpeg(400))
        assertEquals(listOf(FP), c.pendingFiles())
    }
}
