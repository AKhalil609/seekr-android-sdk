package tv.seekr.previews.core.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Rfc3339Test {
    @Test
    fun `parses Z, fractions and offsets`() {
        assertEquals(1_794_736_800_000L, Rfc3339.parseMillis("2026-11-15T10:00:00Z"))
        assertEquals(1_794_736_800_250L, Rfc3339.parseMillis("2026-11-15T10:00:00.25Z"))
        assertEquals(1_794_736_800_123L, Rfc3339.parseMillis("2026-11-15T10:00:00.123456789Z"))
        assertEquals(1_794_736_800_000L, Rfc3339.parseMillis("2026-11-15T12:00:00+02:00"))
        assertEquals(1_794_736_800_000L, Rfc3339.parseMillis("2026-11-15T05:30:00-04:30"))
        assertEquals(0L, Rfc3339.parseMillis("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `rejects everything else`() {
        for (bad in listOf("", "soon", "2026-11-15", "2026-11-15 10:00:00Z", "2026-13-15T10:00:00Z", "2026-11-15T10:00:00", "2026-02-30T10:00:00Z")) {
            assertNull(Rfc3339.parseMillis(bad), bad)
        }
    }
}
