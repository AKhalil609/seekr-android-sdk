package tv.seekr.previews.core.contrib

import kotlin.test.Test
import kotlin.test.assertEquals

/** PACKAGE-SPEC §7.2 / DESIGN-v2 §17.1. */
class FpTest {
    @Test
    fun `torrent vector`() {
        assertEquals(
            "13c37773434af69a78d3983aae188b6e3b4f5b71c545ba85b64f6da1c9f76393",
            Fp.fromTorrent("0123456789ABCDEF0123456789ABCDEF01234567", 2),
        )
    }

    @Test
    fun `file vector`() {
        assertEquals(
            "bd0f05dbcacf6c68ddd110722d2a659e0c1001ca4fdf7b2473765ab8e2d75b30",
            Fp.fromFile("The.Matrix.1999.1080p.BluRay.x264.mkv", 8_589_934_592L),
        )
    }

    @Test
    fun `case of the input does not matter`() {
        assertEquals(Fp.fromTorrent("0123456789abcdef0123456789abcdef01234567", 2), Fp.fromTorrent("0123456789ABCDEF0123456789ABCDEF01234567", 2))
        assertEquals(Fp.fromFile("the.matrix.1999.1080p.bluray.x264.mkv", 8_589_934_592L), Fp.fromFile("The.Matrix.1999.1080p.BluRay.x264.mkv", 8_589_934_592L))
    }

    @Test
    fun `fp is 64 hex and depends on the index`() {
        assertEquals(64, Fp.fromTorrent("a".repeat(40), 0).length)
        assert(Fp.fromTorrent("a".repeat(40), 0) != Fp.fromTorrent("a".repeat(40), 1))
    }

    @Test
    fun `lower-casing is locale independent`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR")) // 'I'.lowercase() is a dotless i there
            assertEquals("bd0f05dbcacf6c68ddd110722d2a659e0c1001ca4fdf7b2473765ab8e2d75b30", Fp.fromFile("The.Matrix.1999.1080p.BluRay.x264.mkv", 8_589_934_592L))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
