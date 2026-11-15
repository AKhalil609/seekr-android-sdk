package tv.seekr.previews.core.contrib

import tv.seekr.previews.core.internal.Base64Url

/**
 * A set of cue indexes in `[0, nSlots)`. Wire form (DESIGN-v2 §6.1): base64url without padding,
 * bit `i` is `1 shl (i and 7)` of byte `i shr 3`, exactly `ceil(nSlots / 8)` bytes, unused high
 * bits zero. Immutable.
 */
class CueSet private constructor(val nSlots: Int, private val bits: ByteArray) : Iterable<Int> {
    val size: Int = bits.sumOf { Integer.bitCount(it.toInt() and 0xFF) }

    val isEmpty: Boolean get() = size == 0

    operator fun contains(cue: Int): Boolean =
        cue in 0 until nSlots && (bits[cue shr 3].toInt() shr (cue and 7)) and 1 == 1

    infix fun union(other: CueSet): CueSet {
        requireSame(other)
        return CueSet(nSlots, ByteArray(bits.size) { (bits[it].toInt() or other.bits[it].toInt()).toByte() })
    }

    operator fun minus(other: CueSet): CueSet {
        requireSame(other)
        return CueSet(nSlots, ByteArray(bits.size) { (bits[it].toInt() and other.bits[it].toInt().inv()).toByte() })
    }

    fun toBase64Url(): String = Base64Url.encode(bits)

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var next = advance(0)

        private fun advance(from: Int): Int {
            var i = from
            while (i < nSlots && (bits[i shr 3].toInt() shr (i and 7)) and 1 == 0) i++
            return i
        }

        override fun hasNext(): Boolean = next < nSlots

        override fun next(): Int {
            if (next >= nSlots) throw NoSuchElementException()
            val cue = next
            next = advance(cue + 1)
            return cue
        }
    }

    override fun equals(other: Any?): Boolean = other is CueSet && nSlots == other.nSlots && bits.contentEquals(other.bits)

    override fun hashCode(): Int = 31 * nSlots + bits.contentHashCode()

    override fun toString(): String = "CueSet(nSlots=$nSlots, cues=${joinToString(",")})"

    private fun requireSame(other: CueSet) =
        require(nSlots == other.nSlots) { "CueSets differ in nSlots (${nSlots} vs ${other.nSlots})" }

    companion object {
        fun empty(nSlots: Int): CueSet {
            require(nSlots >= 0) { "nSlots must not be negative" }
            return CueSet(nSlots, ByteArray(byteLength(nSlots)))
        }

        fun of(nSlots: Int, cues: Iterable<Int>): CueSet {
            require(nSlots >= 0) { "nSlots must not be negative" }
            val bits = ByteArray(byteLength(nSlots))
            for (cue in cues) {
                require(cue in 0 until nSlots) { "cue $cue outside [0, $nSlots)" }
                bits[cue shr 3] = (bits[cue shr 3].toInt() or (1 shl (cue and 7))).toByte()
            }
            return CueSet(nSlots, bits)
        }

        /** `""` is the empty set. Anything else must be exactly `ceil(nSlots / 8)` bytes with the unused high bits clear. */
        fun decode(base64Url: String, nSlots: Int): CueSet {
            require(nSlots >= 0) { "nSlots must not be negative" }
            if (base64Url.isEmpty()) return empty(nSlots)
            val bits = Base64Url.decode(base64Url)
            require(bits.size == byteLength(nSlots)) { "bitmap is ${bits.size} bytes, expected ${byteLength(nSlots)} for $nSlots slots" }
            val spare = nSlots and 7
            if (spare != 0) {
                require(bits.last().toInt() and 0xFF shr spare == 0) { "bitmap has bits set beyond nSlots" }
            }
            return CueSet(nSlots, bits)
        }

        private fun byteLength(nSlots: Int): Int = (nSlots + 7) / 8
    }
}
