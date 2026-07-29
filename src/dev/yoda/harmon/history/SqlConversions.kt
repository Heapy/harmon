package dev.yoda.harmon.history

import kotlin.time.Instant

/**
 * The instant as `sample.captured_at` stores it: ISO-8601 UTC truncated to whole seconds, so the
 * string is always exactly `YYYY-MM-DDTHH:MM:SSZ`.
 *
 * Fixed width is the requirement, not the precision. [Instant.toString] prints 0, 3, 6 or 9
 * fractional digits depending on the value, and
 * `'2026-07-29T00:05:00.500Z' < '2026-07-29T00:05:00Z'` — the later moment sorts first.
 * `ORDER BY captured_at` and the retention cutoff both compare these strings, so a variable-width
 * fraction would silently reorder history and strand rows past the cutoff. The sampling interval
 * is hundreds of seconds; sub-second precision carries no information.
 */
fun Instant.toSqlTimestamp(): String = Instant.fromEpochSeconds(epochSeconds).toString()

/**
 * The same count as a SQLite INTEGER, clamped at [Long.MAX_VALUE].
 *
 * SQLite has no unsigned integer, so a `ULong` past the signed boundary would be stored as a
 * negative number that no reader could tell from a genuine one. Clamping only distorts counters
 * above 9.2 exabytes, which no machine reports, and keeps the column monotonic.
 *
 * Nullable fields go through the same function with `?.` — a separate overload would only add a
 * place for the two to drift apart.
 */
fun ULong.toSqlLong(): Long = coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
