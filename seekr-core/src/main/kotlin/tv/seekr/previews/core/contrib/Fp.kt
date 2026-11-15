package tv.seekr.previews.core.contrib

import java.security.MessageDigest
import java.util.Locale

/**
 * The source-file fingerprint (PACKAGE-SPEC §7.2). The server never interprets it and stores
 * only an HMAC of it while a job is open; it exists so two players of the same file land in
 * the same job without either revealing a file name.
 */
object Fp {
    private const val PREFIX = "seekr-fp-v1"

    /** Torrent form: `sha256("seekr-fp-v1" 0x00 lower(infoHash) ":" fileIdx)`. Prefer it whenever an info-hash is known. */
    fun fromTorrent(infoHash: String, fileIdx: Int): String = derive(infoHash, fileIdx.toString())

    /** File form: [filename] is the base name (no directory); it is lower-cased with [Locale.ROOT] here. */
    fun fromFile(filename: String, sizeBytes: Long): String = derive(filename, sizeBytes.toString())

    private fun derive(name: String, number: String): String {
        val message = "$PREFIX\u0000${name.lowercase(Locale.ROOT)}:$number".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val hex = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xFF
            hex[i * 2] = HEX[b shr 4]
            hex[i * 2 + 1] = HEX[b and 15]
        }
        return String(hex)
    }

    private const val HEX = "0123456789abcdef"
}
