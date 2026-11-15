package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HmacBase64UrlTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `hmac sha256 matches RFC 4231 case 1`() {
        val key = ByteArray(20) { 0x0b }
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hex(Hmac.sha256(key, "Hi There".toByteArray())))
    }

    @Test
    fun `base64url is unpadded and url-safe`() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertEquals("_w", Base64Url.encode(byteArrayOf(0xFF.toByte())))
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertEquals("Zm9v", Base64Url.encode("foo".toByteArray()))
    }

    @Test
    fun `decode round-trips every length`() {
        for (n in 0..40) {
            val data = ByteArray(n) { (it * 37 + n).toByte() }
            assertContentEquals(data, Base64Url.decode(Base64Url.encode(data)))
        }
    }

    @Test
    fun `decode refuses padding, the standard alphabet, whitespace, impossible lengths and stray bits`() {
        for (bad in listOf("Zg==", "+/8", "Zm9v ", "Z", "Zh")) {
            assertFailsWith<IllegalArgumentException>(bad) { Base64Url.decode(bad) }
        }
    }
}
