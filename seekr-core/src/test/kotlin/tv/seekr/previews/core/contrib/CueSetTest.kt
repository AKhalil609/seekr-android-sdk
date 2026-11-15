package tv.seekr.previews.core.contrib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DESIGN-v2 §17.3 bitmap vectors plus the set algebra. */
class CueSetTest {
    @Test
    fun `bitmap vector from 17-3`() {
        val set = CueSet.of(12, listOf(0, 3, 8, 9))
        assertEquals("CQM", set.toBase64Url()) // bytes 09 03
        assertEquals(set, CueSet.decode("CQM", 12))
    }

    @Test
    fun `overlap sample vector`() {
        val overlap = CueSet.of(40, listOf(7, 8, 18, 27, 32, 33))
        assertEquals("gAEECAM", overlap.toBase64Url())
        assertEquals(listOf(7, 8, 18, 27, 32, 33), CueSet.decode("gAEECAM", 40).toList())
    }

    @Test
    fun `worked needs example`() {
        val needed = CueSet.decode("AAAAwP8", 40)
        assertEquals((30..39).toList(), needed.toList())
        val overlap = CueSet.decode("4AEECAA", 40)
        assertEquals(listOf(5, 6, 7, 8, 18, 27), overlap.toList())
        val capture = needed union overlap
        assertEquals(16, capture.size)
        assertEquals(listOf(5, 6, 7, 8, 18, 27) + (30..39), capture.toList())
        assertEquals(capture, CueSet.decode(capture.toBase64Url(), 40))
    }

    @Test
    fun `empty string is the empty set and empty encodes to zero bytes`() {
        val e = CueSet.decode("", 40)
        assertTrue(e.isEmpty)
        assertEquals(0, e.size)
        assertEquals("AAAAAAA", e.toBase64Url())
        assertEquals("", CueSet.empty(0).toBase64Url())
    }

    @Test
    fun `contains is false outside the range`() {
        val s = CueSet.of(10, listOf(0, 9))
        assertTrue(0 in s)
        assertTrue(9 in s)
        assertFalse(5 in s)
        assertFalse(10 in s)
        assertFalse(-1 in s)
    }

    @Test
    fun `union and minus`() {
        val a = CueSet.of(20, listOf(1, 2, 3, 19))
        val b = CueSet.of(20, listOf(3, 4, 19))
        assertEquals(listOf(1, 2, 3, 4, 19), (a union b).toList())
        assertEquals(listOf(1, 2), (a - b).toList())
        assertTrue((a - a).isEmpty)
        assertFailsWith<IllegalArgumentException> { a union CueSet.empty(21) }
        assertFailsWith<IllegalArgumentException> { a - CueSet.empty(8) }
    }

    @Test
    fun `iteration is ascending and the size is the popcount`() {
        val s = CueSet.of(100, listOf(99, 0, 64, 7, 8, 8))
        assertEquals(listOf(0, 7, 8, 64, 99), s.toList())
        assertEquals(5, s.size)
    }

    @Test
    fun `of refuses out of range cues`() {
        assertFailsWith<IllegalArgumentException> { CueSet.of(10, listOf(10)) }
        assertFailsWith<IllegalArgumentException> { CueSet.of(10, listOf(-1)) }
    }

    @Test
    fun `decode refuses malformed bitmaps`() {
        assertFailsWith<IllegalArgumentException> { CueSet.decode("CQM=", 12) } // padding
        assertFailsWith<IllegalArgumentException> { CueSet.decode("CQ", 12) } // 1 byte, needs 2
        assertFailsWith<IllegalArgumentException> { CueSet.decode("CQMA", 12) } // 3 bytes
        assertFailsWith<IllegalArgumentException> { CueSet.decode("CQ+", 12) } // standard alphabet
        assertFailsWith<IllegalArgumentException> { CueSet.decode("CR8", 12) } // bit 12 set, beyond nSlots
    }

    @Test
    fun `round trip for every size around byte boundaries`() {
        for (n in listOf(1, 7, 8, 9, 15, 16, 17, 360)) {
            val s = CueSet.of(n, (0 until n step 3).toList())
            assertEquals(s, CueSet.decode(s.toBase64Url(), n))
        }
    }
}
