package tv.seekr.previews.core.internal

/** base64url without padding. `java.util.Base64` needs API 26 and this library supports API 21. */
internal object Base64Url {
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8) or (bytes[i + 2].toInt() and 0xFF)
            out.append(URL_SAFE[n shr 18 and 63]).append(URL_SAFE[n shr 12 and 63]).append(URL_SAFE[n shr 6 and 63]).append(URL_SAFE[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                out.append(URL_SAFE[n shr 18 and 63]).append(URL_SAFE[n shr 12 and 63])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                out.append(URL_SAFE[n shr 18 and 63]).append(URL_SAFE[n shr 12 and 63]).append(URL_SAFE[n shr 6 and 63])
            }
        }
        return out.toString()
    }

    /**
     * Strict: only the url-safe alphabet, no padding, no whitespace, and a length that a real
     * encoder can produce (length % 4 == 1 never is). Non-zero trailing bits are refused too,
     * so `decode(encode(x))` is the only way to a given string.
     */
    fun decode(text: String): ByteArray {
        require(text.length % 4 != 1) { "invalid base64url length ${text.length}" }
        val out = ByteArray(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in text) {
            val v = URL_SAFE.indexOf(c)
            require(v >= 0) { "invalid base64url character" }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc shr bits and 0xFF).toByte()
            }
        }
        require(acc and ((1 shl bits) - 1) == 0) { "non-zero trailing bits in base64url" }
        return out
    }
}
