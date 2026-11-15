package tv.seekr.previews.core.internal

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** `java.time` needs API 26, so the one timestamp the contribution API returns is parsed by hand. */
internal object Rfc3339 {
    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2})$""")

    /** Epoch milliseconds, or `null` when [text] is not an RFC 3339 date-time. */
    fun parseMillis(text: String): Long? {
        val m = PATTERN.matchEntire(text) ?: return null
        val g = m.groupValues
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            isLenient = false
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        val base = runCatching { cal.timeInMillis }.getOrNull() ?: return null
        val fractionMs = if (g[7].isEmpty()) 0L else g[7].padEnd(3, '0').take(3).toLong()
        val offsetMs = when (val z = g[8]) {
            "Z", "z" -> 0L
            else -> {
                val sign = if (z[0] == '-') -1 else 1
                sign * (z.substring(1, 3).toLong() * 3_600_000L + z.substring(4, 6).toLong() * 60_000L)
            }
        }
        return base + fractionMs - offsetMs
    }
}
