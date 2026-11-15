package tv.seekr.previews.core.contrib

/** A structurally valid package, as the server pre-check sees it. */
internal class NvmtPackage(val gridMs: Int, val nSlots: Int, val tiles: List<Tile>) {
    class Tile(val slot: Int, val ptsMs: Long, val jpeg: ByteArray)
}

/** A PACKAGE-SPEC §3 pre-check rule was broken. [code] is `bad_format`, `grid_unsupported` or `tile_size`. */
internal class NvmtFormatException(val code: String, detail: String) : Exception("$code: $detail")

/**
 * Parser for NVMT v1 with the same rules as Zoetrope's `nvmt.parse` (two passes: byte structure,
 * then SOF dimensions). The SDK itself only writes packages; this exists so tests can prove the
 * writer's output is what the server accepts, and that the golden invalid vectors are refused.
 * Envelope comparisons (claimed duration) are the caller's.
 */
internal object NvmtReader {
    fun parse(data: ByteArray): NvmtPackage {
        if (data.size > NvmtWriter.MAX_PACKAGE_BYTES) bad("package is ${data.size} bytes")
        if (data.size < NvmtWriter.HEADER_BYTES) bad("truncated header (${data.size} bytes)")
        if (data[0] != 0x4E.toByte() || data[1] != 0x56.toByte() || data[2] != 0x4D.toByte() || data[3] != 0x54.toByte()) bad("wrong magic")
        val version = u32(data, 4)
        if (version != 1L) bad("unsupported version $version")
        val gridMs = u32(data, 8)
        if (gridMs != 5_000L && gridMs != 10_000L) throw NvmtFormatException("grid_unsupported", "grid_ms $gridMs")
        val nSlots = u32(data, 12)
        val tileCount = u32(data, 16)
        if (tileCount > nSlots) bad("tile_count $tileCount exceeds n_slots $nSlots")
        if (nSlots > Int.MAX_VALUE) bad("n_slots $nSlots too large")

        val tiles = ArrayList<NvmtPackage.Tile>()
        var pos = NvmtWriter.HEADER_BYTES
        var prevSlot = -1L
        for (k in 0 until tileCount.toInt()) {
            if (pos + NvmtWriter.RECORD_BYTES > data.size) bad("truncated record header for tile $k")
            val slot = u32(data, pos)
            val pts = u32(data, pos + 4)
            val size = u32(data, pos + 8)
            pos += NvmtWriter.RECORD_BYTES
            if (slot >= nSlots) bad("tile $k: slot $slot >= n_slots $nSlots")
            if (slot <= prevSlot) bad("tile $k: slot $slot not strictly increasing")
            if (size < NvmtWriter.MIN_TILE_BYTES || size > NvmtWriter.MAX_TILE_BYTES) bad("tile $k: size $size out of range")
            if (pos + size > data.size) bad("tile $k: truncated JPEG")
            if (data[pos] != 0xFF.toByte() || data[pos + 1] != 0xD8.toByte()) bad("tile $k: JPEG does not start with FF D8")
            tiles += NvmtPackage.Tile(slot.toInt(), pts, data.copyOfRange(pos, pos + size.toInt()))
            pos += size.toInt()
            prevSlot = slot
        }
        if (pos != data.size) bad("${data.size - pos} trailing bytes after the last tile")

        tiles.forEachIndexed { k, t ->
            val (w, h) = jpegDimensions(t.jpeg) ?: bad("tile $k: no JPEG frame header found")
            if (w != 320 || h != 180) throw NvmtFormatException("tile_size", "tile $k: ${w}x$h, expected 320x180")
        }
        return NvmtPackage(gridMs.toInt(), nSlots.toInt(), tiles)
    }

    /** `(width, height)` from the SOF0/1/2 marker without decoding, or null when it cannot be found. */
    fun jpegDimensions(jpeg: ByteArray): Pair<Int, Int>? {
        val n = jpeg.size
        fun b(i: Int) = jpeg[i].toInt() and 0xFF
        if (n < 4 || b(0) != 0xFF || b(1) != 0xD8) return null
        var i = 2
        while (i < n) {
            if (b(i) != 0xFF) return null
            while (i < n && b(i) == 0xFF) i++ // any number of fill bytes may precede a marker
            if (i >= n) return null
            val marker = b(i)
            i++
            if (marker == 0x01 || marker in 0xD0..0xD8) continue // standalone markers carry no length
            if (marker == 0xD9 || marker == 0xDA) return null // EOI / SOS before any frame header
            if (i + 2 > n) return null
            val segLen = (b(i) shl 8) or b(i + 1)
            if (segLen < 2) return null
            if (marker in 0xC0..0xC2) {
                if (segLen < 7 || i + 7 > n) return null
                return ((b(i + 5) shl 8) or b(i + 6)) to ((b(i + 3) shl 8) or b(i + 4))
            }
            i += segLen
        }
        return null
    }

    private fun bad(detail: String): Nothing = throw NvmtFormatException("bad_format", detail)

    private fun u32(d: ByteArray, at: Int): Long =
        (d[at].toLong() and 0xFF) or (d[at + 1].toLong() and 0xFF shl 8) or
            (d[at + 2].toLong() and 0xFF shl 16) or (d[at + 3].toLong() and 0xFF shl 24)
}
