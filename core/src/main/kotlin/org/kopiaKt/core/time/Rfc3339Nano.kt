package org.kopiaKt.core.time

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val NANO_DIGIT_COUNT = 9

// Formats the whole-seconds part in UTC; the fractional part is appended by formatRfc3339Nano.
private val SECONDS_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

/**
 * Formats [instant] like Go's `time.RFC3339Nano`: UTC with a `Z` suffix, and a fractional-second part
 * with ALL trailing zeros trimmed (and no fraction at all when nanos == 0).
 *
 * This exists because `DateTimeFormatter.ISO_INSTANT` / `Instant.toString()` instead pad the fraction
 * to 3/6/9-digit groups (nanos = 500000000 → `.500Z` vs Go `.5Z`). Kopia manifest and snapshot JSON is
 * content-addressed, so that one-character difference changes the content hash and breaks dedup and
 * cross-verification against Go Kopia. All Instant serializers in the codebase must use this so their
 * output is byte-identical to Go. (Kopia snapshot/manifest times are `fs.UTCTimestamp` — always UTC and
 * within ~1677-2262, so the `Z` suffix and the year format are always correct for them.)
 */
fun formatRfc3339Nano(instant: Instant): String {
    val base = SECONDS_FORMATTER.format(instant)
    val nanos = instant.nano
    if (nanos == 0) return "${base}Z"
    val fraction = nanos.toString().padStart(NANO_DIGIT_COUNT, '0').trimEnd('0')
    return "$base.${fraction}Z"
}
