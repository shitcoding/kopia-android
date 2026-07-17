package org.kopiaKt.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Byte-exactness of [formatRfc3339Nano] against Go's `time.RFC3339Nano`. The expected strings here were
 * confirmed against the real Go runtime (`t.Format(time.RFC3339Nano)`) for the same nanosecond values.
 */
class Rfc3339NanoTest {

    private fun at(nanos: Long) =
        formatRfc3339Nano(Instant.parse("2024-01-15T10:30:00Z").plusNanos(nanos))

    @Test
    fun `trims trailing zeros and omits the fraction when nanos is zero`() {
        assertEquals("2024-01-15T10:30:00Z", at(0L))
        assertEquals("2024-01-15T10:30:00.5Z", at(500_000_000L))
        assertEquals("2024-01-15T10:30:00.12Z", at(120_000_000L))
        assertEquals("2024-01-15T10:30:00.000001Z", at(1_000L))
        assertEquals("2024-01-15T10:30:00.123456789Z", at(123_456_789L))
    }

    @Test
    fun `EPOCH formats as UTC with Z and no fraction`() {
        assertEquals("1970-01-01T00:00:00Z", formatRfc3339Nano(Instant.EPOCH))
    }

    @Test
    fun `pre-1970 instants keep a positive nano fraction`() {
        // Instant.nano is always in [0, 999999999], even for negative epoch seconds.
        assertEquals(
            "1969-12-31T23:59:59.5Z",
            formatRfc3339Nano(Instant.ofEpochSecond(-1, 500_000_000))
        )
    }
}
