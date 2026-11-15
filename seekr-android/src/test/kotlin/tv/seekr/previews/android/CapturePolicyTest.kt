package tv.seekr.previews.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tv.seekr.previews.android.internal.Backoff
import tv.seekr.previews.android.internal.CapturePolicy

class CapturePolicyTest {
    private val config = ContributorConfig()

    private fun snap(
        positionMs: Long = 20_000,
        isPlaying: Boolean = true,
        speed: Float = 1f,
        sinceSeek: Long = Long.MAX_VALUE,
        sinceStall: Long = Long.MAX_VALUE,
        hdr: Boolean = false,
        secure: Boolean = false,
        dropped: Int = 0,
        thermal: Boolean = false,
    ) = PlaybackSnapshot(positionMs, isPlaying, speed, sinceSeek, sinceStall, 120_000, hdr, secure, dropped, thermal)

    @Test
    fun `plain playback at 1x allows a capture`() {
        assertNull(CapturePolicy.waitReason(snap(), config))
    }

    @Test
    fun `paused, other speeds, recent seek and recent stall each wait with their own reason`() {
        assertEquals(WaitReason.Paused, CapturePolicy.waitReason(snap(isPlaying = false), config))
        assertEquals(WaitReason.NotNormalSpeed, CapturePolicy.waitReason(snap(speed = 2f), config))
        assertEquals(WaitReason.NotNormalSpeed, CapturePolicy.waitReason(snap(speed = 0.5f), config))
        assertEquals(WaitReason.Seeking, CapturePolicy.waitReason(snap(sinceSeek = 2_999), config))
        assertNull(CapturePolicy.waitReason(snap(sinceSeek = 3_000), config))
        assertEquals(WaitReason.Buffering, CapturePolicy.waitReason(snap(sinceStall = 1_999), config))
        assertNull(CapturePolicy.waitReason(snap(sinceStall = 2_000), config))
    }

    @Test
    fun `hdr secure throttling and dropped frames stop the session`() {
        assertNull(CapturePolicy.sessionStop(snap(), config, false))
        assertEquals(StopReason.Hdr, CapturePolicy.sessionStop(snap(hdr = true), config, false))
        assertEquals(StopReason.SecureSurface, CapturePolicy.sessionStop(snap(secure = true), config, false))
        assertEquals(StopReason.Throttled, CapturePolicy.sessionStop(snap(thermal = true), config, false))
        assertEquals(StopReason.Throttled, CapturePolicy.sessionStop(snap(), config, deviceThrottled = true))
        assertNull(CapturePolicy.sessionStop(snap(dropped = 29), config, false))
        assertEquals(StopReason.DroppedFrames, CapturePolicy.sessionStop(snap(dropped = 30), config, false))
    }

    @Test
    fun `the cue window is 10 s i to 10 s i plus 1 s inclusive`() {
        assertEquals(0, CapturePolicy.cueInWindow(0, config))
        assertEquals(0, CapturePolicy.cueInWindow(1_000, config))
        assertNull(CapturePolicy.cueInWindow(1_001, config))
        assertNull(CapturePolicy.cueInWindow(9_999, config))
        assertEquals(1, CapturePolicy.cueInWindow(10_000, config))
        assertEquals(7, CapturePolicy.cueInWindow(70_500, config))
        assertNull(CapturePolicy.cueInWindow(-1, config))
    }

    @Test
    fun `pts must be known and within 3 s of the cue`() {
        assertTrue(CapturePolicy.ptsAcceptable(50_000, 5, config))
        assertTrue(CapturePolicy.ptsAcceptable(51_000, 5, config))
        assertTrue(CapturePolicy.ptsAcceptable(53_000, 5, config))
        assertTrue(CapturePolicy.ptsAcceptable(47_000, 5, config))
        assertFalse(CapturePolicy.ptsAcceptable(53_001, 5, config))
        assertFalse(CapturePolicy.ptsAcceptable(46_999, 5, config))
        assertFalse(CapturePolicy.ptsAcceptable(-1, 0, config))
    }

    @Test
    fun `backoff doubles from 1 min up to 6 h and resets on success`() {
        var now = 0L
        val b = Backoff(config, { now })
        val delays = ArrayList<Long>()
        repeat(12) {
            b.failed(null)
            delays += b.remainingMs()
            now += b.remainingMs()
        }
        assertEquals(60_000L, delays[0])
        assertEquals(120_000L, delays[1])
        assertEquals(240_000L, delays[2])
        assertEquals(6L * 60 * 60 * 1000, delays.last())
        assertTrue(delays.zipWithNext().all { (a, c) -> c >= a })
        b.succeeded()
        assertEquals(0L, b.remainingMs())
        b.failed(null)
        assertEquals(60_000L, b.remainingMs())
    }

    @Test
    fun `backoff honours Retry-After as given and bounds it`() {
        var now = 0L
        val b = Backoff(config, { now })
        b.failed(retryAfterSec = 90)
        assertEquals(90_000L, b.remainingMs())
        assertTrue(b.active)
        now += 90_000
        assertFalse(b.active)
        b.failed(retryAfterSec = 10 * 24 * 3600)
        assertEquals(config.backoffMaxMs, b.remainingMs())
        // a Retry-After does not advance the exponential schedule
        b.succeeded()
        b.failed(retryAfterSec = 5)
        b.failed(retryAfterSec = null)
        assertEquals(60_000L, b.remainingMs())
    }
}
