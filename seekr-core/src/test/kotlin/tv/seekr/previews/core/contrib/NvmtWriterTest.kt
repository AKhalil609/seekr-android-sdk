package tv.seekr.previews.core.contrib

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Golden vectors shared with Zoetrope (`tests/fixtures/nvmt/`, PACKAGE-SPEC §6): the writer must reproduce the
 * valid packages byte for byte from their own tiles, and the reader must refuse the invalid ones with the code
 * the server pre-check gives.
 */
class NvmtWriterTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/nvmt/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private val vectors: List<JsonObject> =
        Json.parseToJsonElement(String(resource("vectors.json"))).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun reencode(pkg: NvmtPackage): ByteArray {
        val w = NvmtWriter(pkg.nSlots, pkg.gridMs)
        pkg.tiles.forEach { w.addSlot(it.slot, it.ptsMs, it.jpeg) }
        return w.build()
    }

    @Test
    fun `valid vectors are reproduced byte for byte`() {
        val valid = vectors.filter { it["stage"]!!.jsonPrimitive.content == "ingest" && it["expect"]!!.jsonPrimitive.content == "ok" }
        assertTrue(valid.size >= 2)
        for (v in valid) {
            val file = v["file"]!!.jsonPrimitive.content
            val original = resource(file)
            assertEquals(v["sha256"]!!.jsonPrimitive.content, sha256(original), "fixture $file is intact")
            val rebuilt = reencode(NvmtReader.parse(original))
            assertContentEquals(original, rebuilt, "re-encoding $file")
        }
    }

    @Test
    fun `cue-addressed add on the 10 s grid equals the slot form`() {
        val original = resource("valid_10s_grid.nvmt")
        val pkg = NvmtReader.parse(original)
        val w = NvmtWriter(pkg.nSlots)
        pkg.tiles.forEach { w.add(it.slot, it.ptsMs, it.jpeg) }
        assertEquals(pkg.tiles.size, w.tileCount)
        assertContentEquals(original, w.build())
    }

    @Test
    fun `cue maps to slot 2 x cue on the 5 s grid`() {
        val pkg = NvmtReader.parse(resource("valid_5s_grid.nvmt"))
        val w = NvmtWriter(pkg.nSlots, 5_000)
        val even = pkg.tiles.filter { it.slot % 2 == 0 }
        even.forEach { w.add(it.slot / 2, it.ptsMs, it.jpeg) }
        assertEquals(even.map { it.slot }, NvmtReader.parse(w.build()).tiles.map { it.slot })
    }

    @Test
    fun `invalid precheck vectors are refused with the server's code`() {
        val precheck = vectors.filter { it["stage"]!!.jsonPrimitive.content == "precheck" }
        assertTrue(precheck.size >= 14)
        for (v in precheck) {
            val id = v["id"]!!.jsonPrimitive.content
            val expected = v["expect"]!!.jsonPrimitive.content
            val data = resource(v["file"]!!.jsonPrimitive.content)
            val claimedMs = v["claim"]!!.jsonObject["duration_ms"]!!.jsonPrimitive.long
            val code = try {
                val pkg = NvmtReader.parse(data)
                // the envelope rule of PACKAGE-SPEC §3 (header vs. the claimed duration)
                if (Math.abs(pkg.nSlots.toLong() * pkg.gridMs - claimedMs) > 2L * pkg.gridMs) "bad_format" else "ok"
            } catch (e: NvmtFormatException) {
                e.code
            }
            assertEquals(expected, code, id)
        }
    }

    @Test
    fun `the valid vectors pass the reader and the envelope rule`() {
        for (v in vectors.filter { it["expect"]!!.jsonPrimitive.content == "ok" }) {
            val pkg = NvmtReader.parse(resource(v["file"]!!.jsonPrimitive.content))
            val claimedMs = v["claim"]!!.jsonObject["duration_ms"]!!.jsonPrimitive.long
            assertTrue(Math.abs(pkg.nSlots.toLong() * pkg.gridMs - claimedMs) <= 2L * pkg.gridMs)
        }
    }

    // ---- writer rules ----

    private val tile: ByteArray = NvmtReader.parse(resource("valid_10s_grid.nvmt")).tiles[0].jpeg

    @Test
    fun `header layout and the maximum constant`() {
        val w = NvmtWriter(36)
        w.add(3, 31_000, tile)
        val bytes = w.build()
        assertEquals("NVMT", String(bytes, 0, 4))
        assertContentEquals(byteArrayOf(1, 0, 0, 0, 0x10, 0x27, 0, 0, 36, 0, 0, 0, 1, 0, 0, 0), bytes.copyOfRange(4, 20))
        assertContentEquals(byteArrayOf(3, 0, 0, 0), bytes.copyOfRange(20, 24))
        assertEquals(20 + 12 + tile.size, bytes.size)
        assertEquals(16 * 1024 * 1024, NvmtWriter.MAX_PACKAGE_BYTES)
    }

    @Test
    fun `build needs a tile and can be called again after more adds`() {
        val w = NvmtWriter(10)
        assertFailsWith<IllegalStateException> { w.build() }
        w.add(0, 0, tile)
        val first = w.build()
        w.add(1, 10_000, tile)
        assertEquals(first.size + 12 + tile.size, w.build().size)
    }

    @Test
    fun `cues and pts must strictly increase`() {
        val w = NvmtWriter(10)
        w.add(2, 20_000, tile)
        assertFailsWith<IllegalArgumentException> { w.add(2, 21_000, tile) }
        assertFailsWith<IllegalArgumentException> { w.add(1, 21_000, tile) }
        assertFailsWith<IllegalArgumentException> { w.add(3, 20_000, tile) }
        assertFailsWith<IllegalArgumentException> { w.add(3, 19_000, tile) }
        w.add(3, 20_001, tile)
        assertEquals(2, w.tileCount) // rejected tiles left no trace
    }

    @Test
    fun `cue must lie inside the grid`() {
        val w = NvmtWriter(5)
        assertFailsWith<IllegalArgumentException> { w.add(5, 50_000, tile) }
        assertFailsWith<IllegalArgumentException> { w.add(-1, 0, tile) }
    }

    @Test
    fun `tile size and magic are enforced`() {
        val w = NvmtWriter(5)
        assertFailsWith<IllegalArgumentException> { w.add(0, 0, ByteArray(124) { if (it == 0) 0xFF.toByte() else 0xD8.toByte() }) }
        assertFailsWith<IllegalArgumentException> { w.add(0, 0, ByteArray(200_001).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }) }
        assertFailsWith<IllegalArgumentException> { w.add(0, 0, ByteArray(200)) }
        assertFailsWith<IllegalArgumentException> { w.add(0, -1, tile) }
        assertEquals(0, w.tileCount)
        w.add(0, 0, ByteArray(125).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() })
        w.add(1, 10_000, ByteArray(200_000).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() })
        assertEquals(2, w.tileCount)
    }

    @Test
    fun `constructor rules`() {
        assertFailsWith<IllegalArgumentException> { NvmtWriter(0) }
        assertFailsWith<IllegalArgumentException> { NvmtWriter(10, 7_000) }
        NvmtWriter(10, 5_000)
    }

    @Test
    fun `the 16 MiB package cap is enforced when adding`() {
        val w = NvmtWriter(200)
        val big = ByteArray(200_000).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        var cue = 0
        while (true) {
            try {
                w.add(cue, cue * 10_000L, big)
            } catch (e: IllegalArgumentException) {
                break
            }
            cue++
        }
        assertTrue(w.build().size <= NvmtWriter.MAX_PACKAGE_BYTES)
        assertTrue(cue in 80..84)
    }
}
