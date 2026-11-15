package tv.seekr.previews.core.contrib

import java.io.ByteArrayOutputStream

/**
 * NVMT v1 writer (PACKAGE-SPEC §1): header, then one record per tile, all integers u32
 * little-endian, nothing else. Byte-for-byte what Zoetrope's `nvmt.encode` produces for the same
 * tiles. Checks the structural rules the server pre-check enforces so a bad tile fails here
 * instead of costing a whole upload.
 */
class NvmtWriter(val nSlots: Int, val gridMs: Int = 10_000) {
    init {
        require(nSlots > 0) { "nSlots must be positive" }
        require(gridMs == 5_000 || gridMs == 10_000) { "gridMs must be 5000 or 10000" }
    }

    private val body = ByteArrayOutputStream()
    private var lastSlot = -1
    private var lastPtsMs = -1L

    var tileCount: Int = 0
        private set

    /**
     * [cue] strictly increasing across calls (slot = cue for gridMs 10000, `cue * 2` for 5000), [ptsMs] strictly
     * increasing; [jpeg] 125..200 000 bytes starting FF D8. [IllegalArgumentException] otherwise.
     */
    fun add(cue: Int, ptsMs: Long, jpeg: ByteArray) {
        require(cue >= 0 && cue < nSlots.toLong()) { "cue $cue outside [0, $nSlots)" }
        addSlot(cue * (10_000 / gridMs), ptsMs, jpeg)
    }

    /** Raw-slot form; the package format allows slots that are not cues on a 5 s grid. */
    internal fun addSlot(slot: Int, ptsMs: Long, jpeg: ByteArray) {
        require(slot in 0 until nSlots) { "slot $slot outside [0, $nSlots)" }
        require(slot > lastSlot) { "slot $slot is not after the previous slot $lastSlot" }
        require(ptsMs in 0..U32_MAX) { "ptsMs $ptsMs does not fit a u32" }
        require(ptsMs > lastPtsMs) { "ptsMs $ptsMs is not after the previous ptsMs $lastPtsMs" }
        require(jpeg.size in MIN_TILE_BYTES..MAX_TILE_BYTES) { "tile is ${jpeg.size} bytes, expected $MIN_TILE_BYTES..$MAX_TILE_BYTES" }
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "tile is not a JPEG (no FF D8)" }
        require(HEADER_BYTES + body.size() + RECORD_BYTES + jpeg.size <= MAX_PACKAGE_BYTES) { "package would exceed $MAX_PACKAGE_BYTES bytes" }
        putU32(body, slot.toLong())
        putU32(body, ptsMs)
        putU32(body, jpeg.size.toLong())
        body.write(jpeg)
        lastSlot = slot
        lastPtsMs = ptsMs
        tileCount++
    }

    /** Header + tile records; at least one tile is required. May be called repeatedly. */
    fun build(): ByteArray {
        check(tileCount > 0) { "an NVMT package needs at least one tile" }
        val out = ByteArrayOutputStream(HEADER_BYTES + body.size())
        out.write(MAGIC)
        putU32(out, 1)
        putU32(out, gridMs.toLong())
        putU32(out, nSlots.toLong())
        putU32(out, tileCount.toLong())
        body.writeTo(out)
        return out.toByteArray()
    }

    companion object {
        const val MAX_PACKAGE_BYTES: Int = 16 * 1024 * 1024

        internal const val MIN_TILE_BYTES = 125
        internal const val MAX_TILE_BYTES = 200_000
        internal const val HEADER_BYTES = 20
        internal const val RECORD_BYTES = 12
        private const val U32_MAX = 0xFFFF_FFFFL
        private val MAGIC = byteArrayOf(0x4E, 0x56, 0x4D, 0x54) // "NVMT"

        private fun putU32(out: ByteArrayOutputStream, v: Long) {
            out.write((v and 0xFF).toInt())
            out.write((v shr 8 and 0xFF).toInt())
            out.write((v shr 16 and 0xFF).toInt())
            out.write((v shr 24 and 0xFF).toInt())
        }
    }
}
