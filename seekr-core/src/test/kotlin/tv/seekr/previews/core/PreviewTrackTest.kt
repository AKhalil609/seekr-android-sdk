package tv.seekr.previews.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tv.seekr.previews.core.internal.VttCue

/**
 * [VttCue] and the [PreviewTrack] internal constructor are both `internal`, which the
 * Kotlin Gradle plugin makes visible to the `test` source set of the same module by
 * default (test compilation is associated with main), so no test-only factory is needed.
 */
private fun cue(startMs: Long, endMs: Long, sheetUrl: String) = VttCue(
    startMs = startMs,
    endMs = endMs,
    tile = SeekrTile(sheetUrl = sheetUrl, x = 0, y = 0, w = 320, h = 180),
)

class PreviewTrackTest {

    private fun sampleTrack() = PreviewTrack(
        listOf(
            cue(0L, 10_000L, "sheet-0"),
            cue(10_000L, 20_000L, "sheet-1"),
            cue(20_000L, 30_000L, "sheet-2"),
        ),
    )

    @Test
    fun `zero offset matches current behaviour`() {
        val track = sampleTrack()
        assertEquals("sheet-0", track.tileAt(0L)?.sheetUrl)
        assertEquals("sheet-0", track.tileAt(5_000L)?.sheetUrl)
        assertEquals("sheet-1", track.tileAt(10_000L)?.sheetUrl)
        assertEquals("sheet-1", track.tileAt(15_000L)?.sheetUrl)
        assertEquals("sheet-2", track.tileAt(29_999L)?.sheetUrl)
        // Past the last cue, current behaviour returns the last tile (no upper bound check).
        assertEquals("sheet-2", track.tileAt(100_000L)?.sheetUrl)
    }

    @Test
    fun `positive offset shifts lookup forward, preview timeline runs ahead`() {
        val track = sampleTrack()
        track.offsetMs = 10_000L
        // Requesting playback position 0 with a +10s offset looks up 10s in preview time.
        assertEquals("sheet-1", track.tileAt(0L)?.sheetUrl)
        assertEquals("sheet-2", track.tileAt(15_000L)?.sheetUrl)
    }

    @Test
    fun `negative offset shifts lookup backward, preview timeline runs behind`() {
        val track = sampleTrack()
        track.offsetMs = -10_000L
        // Requesting playback position 15s with a -10s offset looks up 5s in preview time.
        assertEquals("sheet-0", track.tileAt(15_000L)?.sheetUrl)
        assertEquals("sheet-1", track.tileAt(25_000L)?.sheetUrl)
    }

    @Test
    fun `sourceDurationMs seeds offsetMs with the correct sign for a longer played release`() {
        // Sprites generated from a 30s SHORTER release: the played release has extra head
        // content (logo/black frames), so a scene at 10_000 in the sprite source sits at
        // 40_000 in playback. Seeding offsetMs from the drift must resolve playback 40_000
        // back to preview 10_000 — i.e. the drift must be NEGATIVE.
        val sourceDurationMs = 60_000L
        val playedDurationMs = 90_000L
        val track = PreviewTrack(
            listOf(
                cue(0L, 10_000L, "sheet-0"),
                cue(10_000L, 20_000L, "sheet-1"),
                cue(20_000L, 30_000L, "sheet-2"),
            ),
            sourceDurationMs = sourceDurationMs,
        )

        val driftMs = track.sourceDurationMs - playedDurationMs
        assertEquals(-30_000L, driftMs, "a longer played release must yield a negative offset")

        track.offsetMs = driftMs
        assertEquals("sheet-1", track.tileAt(40_000L)?.sheetUrl)
    }

    @Test
    fun `sourceDurationMs seeds offsetMs with the correct sign for a shorter played release`() {
        // The reverse: the played release is MISSING head content the sprite source had, so a
        // scene at 40_000 in the sprite source sits at 10_000 in playback. Drift is positive.
        val sourceDurationMs = 90_000L
        val playedDurationMs = 60_000L
        val track = PreviewTrack(
            listOf(
                cue(0L, 10_000L, "sheet-0"),
                cue(10_000L, 20_000L, "sheet-1"),
                cue(20_000L, 30_000L, "sheet-2"),
                cue(30_000L, 40_000L, "sheet-3"),
                cue(40_000L, 50_000L, "sheet-4"),
            ),
            sourceDurationMs = sourceDurationMs,
        )

        val driftMs = track.sourceDurationMs - playedDurationMs
        assertEquals(30_000L, driftMs, "a shorter played release must yield a positive offset")

        track.offsetMs = driftMs
        assertEquals("sheet-4", track.tileAt(10_000L)?.sheetUrl)
    }

    @Test
    fun `offset clamps before the first cue to the first tile`() {
        val track = sampleTrack()
        track.offsetMs = -1_000_000L
        assertEquals("sheet-0", track.tileAt(0L)?.sheetUrl)
        assertEquals("sheet-0", track.tileAt(20_000L)?.sheetUrl)
    }

    @Test
    fun `offset clamps past the last cue to the last tile`() {
        val track = sampleTrack()
        track.offsetMs = 1_000_000L
        assertEquals("sheet-2", track.tileAt(0L)?.sheetUrl)
        assertEquals("sheet-2", track.tileAt(20_000L)?.sheetUrl)
    }

    @Test
    fun `cueAt returns the correct window for a position inside a cue`() {
        val track = sampleTrack()
        val cue = track.cueAt(5_000L)
        assertEquals("sheet-0", cue?.tile?.sheetUrl)
        assertEquals(0L, cue?.startMs)
        assertEquals(10_000L, cue?.endMs)
    }

    @Test
    fun `cueAt returns the correct window exactly on a cue boundary`() {
        val track = sampleTrack()
        val cue = track.cueAt(10_000L)
        assertEquals("sheet-1", cue?.tile?.sheetUrl)
        assertEquals(10_000L, cue?.startMs)
        assertEquals(20_000L, cue?.endMs)
    }

    @Test
    fun `cueAt before the first cue clamps to the first cue`() {
        val track = sampleTrack()
        // No offset applied: a position before the first cue's start still resolves to it
        // because resolveCue clamps to cues.first() when nothing matches.
        val cue = track.cueAt(-5_000L)
        assertEquals("sheet-0", cue?.tile?.sheetUrl)
        assertEquals(0L, cue?.startMs)
        assertEquals(10_000L, cue?.endMs)
    }

    @Test
    fun `cueAt past the last cue clamps to the last cue`() {
        val track = sampleTrack()
        val cue = track.cueAt(100_000L)
        assertEquals("sheet-2", cue?.tile?.sheetUrl)
        assertEquals(20_000L, cue?.startMs)
        assertEquals(30_000L, cue?.endMs)
    }

    @Test
    fun `cueAt on an empty track returns null`() {
        val track = PreviewTrack(emptyList())
        assertNull(track.cueAt(0L))
    }

    @Test
    fun `cueAt and tileAt always agree on the tile they resolve`() {
        val track = sampleTrack()
        for (position in listOf(-5_000L, 0L, 5_000L, 10_000L, 15_000L, 20_000L, 29_999L, 100_000L)) {
            assertEquals(track.tileAt(position), track.cueAt(position)?.tile)
        }
    }

    @Test
    fun `empty track returns null regardless of offset`() {
        val track = PreviewTrack(emptyList())
        assertNull(track.tileAt(0L))
        track.offsetMs = 5_000L
        assertNull(track.tileAt(0L))
        track.offsetMs = -5_000L
        assertNull(track.tileAt(0L))
    }

    @Test
    fun `cueIntervalMs reports the grid spacing`() {
        assertEquals(10_000L, sampleTrack().cueIntervalMs)
    }

    @Test
    fun `cueIntervalMs is a median, so keyframe jitter does not move it`() {
        // Cue starts are snapped to real keyframes, so consecutive gaps vary by a second or
        // two either way even though the underlying grid is uniform. A median reports the
        // grid; a mean reports the grid plus whatever the snapping did.
        val track = PreviewTrack(
            listOf(
                cue(0L, 9_400L, "sheet-0"),
                cue(9_400L, 21_100L, "sheet-1"),
                cue(21_100L, 29_800L, "sheet-2"),
                cue(29_800L, 40_300L, "sheet-3"),
                cue(40_300L, 41_000L, "sheet-4"), // short tail cue
            ),
        )
        assertEquals(10_500L, track.cueIntervalMs)
    }

    @Test
    fun `cueIntervalMs falls back to the generator default for a track too short to measure`() {
        assertEquals(10_000L, PreviewTrack(listOf(cue(0L, 10_000L, "sheet-0"))).cueIntervalMs)
        assertEquals(10_000L, PreviewTrack(emptyList()).cueIntervalMs)
    }

    @Test
    fun `stepCue walks cue to cue and clamps at the ends`() {
        val track = sampleTrack()
        assertEquals(10_000L, track.stepCue(5_000L, 1)?.startMs.let { it })
        assertEquals(20_000L, track.stepCue(10_000L, 1)?.startMs)
        assertEquals(20_000L, track.stepCue(20_000L, 1)?.startMs) // end of the track
        assertEquals(0L, track.stepCue(0L, -1)?.startMs) // start of the track
        assertEquals(0L, track.stepCue(10_000L, -1)?.startMs)
    }

    @Test
    fun `stepping back from the middle of a cue lands on that cue's own start first`() {
        val track = sampleTrack()
        assertEquals(10_000L, track.stepCue(18_500L, -1)?.startMs)
        assertEquals(0L, track.stepCue(18_500L, -2)?.startMs)
        assertEquals(20_000L, track.stepCue(18_500L, 1)?.startMs)
    }

    @Test
    fun `stepCue works on the preview timeline and toPlaybackMs undoes the offset`() {
        val track = sampleTrack()
        track.offsetMs = -4_000L // the played release has 4s extra at the head
        // playback 16s is preview 12s → cue 10s; one step on is the 20s cue, shown at playback 24s
        val next = track.stepCue(16_000L, 1)
        assertEquals(20_000L, next?.startMs)
        assertEquals(24_000L, track.toPlaybackMs(next!!.startMs))
    }

    @Test
    fun `stepCue on an empty track is null`() {
        assertNull(PreviewTrack(emptyList()).stepCue(0L, 1))
    }
}
