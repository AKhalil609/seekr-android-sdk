package tv.seekr.previews.android

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Letterbox geometry against Zoetrope's `normalize`: fit inside 320x180 (floor), centre (floor) on black. */
class TileEncoderTest {
    private val black = 0xFF000000.toInt()
    private val colour = 0xFFC86432.toInt() // (200, 100, 50)

    private fun solid(w: Int, h: Int, argb: Int = colour) = IntArray(w * h) { argb }

    private fun channels(p: Int) = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    private fun near(expected: Int, actual: Int, tol: Int = 2) =
        channels(expected).zip(channels(actual)).all { (a, b) -> abs(a - b) <= tol }

    /** Bounding box (x0, y0, x1, y1 inclusive) of the non-black pixels. */
    private fun content(out: IntArray): List<Int> {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until TileEncoder.TILE_HEIGHT) for (x in 0 until TileEncoder.TILE_WIDTH) {
            if (out[y * TileEncoder.TILE_WIDTH + x] != black) {
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
            }
        }
        return listOf(x0, y0, x1, y1)
    }

    @Test
    fun `output is always 320 by 180 and opaque`() {
        val out = TileEncoder.letterbox(solid(64, 64, 0x80C86432.toInt()), 64, 64)
        assertEquals(320 * 180, out.size)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `16 by 9 fills the tile with no bars`() {
        for ((w, h) in listOf(1920 to 1080, 640 to 360, 320 to 180)) {
            val out = TileEncoder.letterbox(solid(w, h), w, h)
            assertEquals(listOf(0, 0, 319, 179), content(out), "${w}x$h")
            assertTrue(near(colour, out[90 * 320 + 160]))
        }
    }

    @Test
    fun `native 320 by 180 passes through untouched`() {
        val src = IntArray(320 * 180) { (0xFF shl 24) or (it * 7919 and 0xFFFFFF) }
        assertTrue(TileEncoder.letterbox(src, 320, 180).contentEquals(src))
    }

    @Test
    fun `4 by 3 is pillarboxed to 240 wide centred`() {
        // 320x180 box: ratio = min(320/640, 180/480) = 0.375 -> 240x180, offset x = 40.
        val out = TileEncoder.letterbox(solid(640, 480), 640, 480)
        assertEquals(listOf(40, 0, 279, 179), content(out))
        assertEquals(black, out[90 * 320 + 39])
        assertEquals(black, out[90 * 320 + 280])
        assertTrue(near(colour, out[90 * 320 + 40]))
        assertTrue(near(colour, out[90 * 320 + 279]))
    }

    @Test
    fun `2 39 to 1 is letterboxed to 133 high centred with floor offsets`() {
        // 2048x858: ratio = min(320/2048, 180/858) = 0.15625 -> 320 x floor(134.06) = 134, offset y = (180-134)/2 = 23.
        val out = TileEncoder.letterbox(solid(2048, 858), 2048, 858)
        assertEquals(listOf(0, 23, 319, 156), content(out))
        assertEquals(black, out[22 * 320 + 160])
        assertEquals(black, out[157 * 320 + 160])
    }

    @Test
    fun `an odd leftover is split with the extra pixel at the bottom and right like Zoetrope`() {
        // 100x100 -> ratio 1.8 -> 180x180, offset x = 70 (even). 101x100 -> ratio 1.8 -> floor(181.8) = 181 wide, offset (320-181)/2 = 69.
        val out = TileEncoder.letterbox(solid(101, 100), 101, 100)
        assertEquals(listOf(69, 0, 249, 179), content(out))
    }

    @Test
    fun `a frame smaller than the box is scaled up to fit, as Zoetrope does`() {
        val out = TileEncoder.letterbox(solid(160, 90), 160, 90)
        assertEquals(listOf(0, 0, 319, 179), content(out))
    }

    @Test
    fun `resampling keeps the picture a gradient does not turn into noise`() {
        val w = 1280; val h = 720
        val src = IntArray(w * h) { i -> val x = i % w; val v = x * 255 / (w - 1); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val out = TileEncoder.letterbox(src, w, h)
        val row = 90 * 320
        for (x in 1 until 320) assertTrue(channels(out[row + x])[0] >= channels(out[row + x - 1])[0] - 2, "monotone at $x")
        assertTrue(channels(out[row + 5])[0] < 20 && channels(out[row + 314])[0] > 235)
    }

    @Test
    fun `the input pixel order is argb`() {
        val red = 0xFFFF0000.toInt()
        val out = TileEncoder.letterbox(solid(320, 180, red), 320, 180)
        assertEquals(red, out[0])
        val scaled = TileEncoder.letterbox(solid(640, 360, red), 640, 360)
        assertTrue(near(red, scaled[100 * 320 + 100]))
    }
}
