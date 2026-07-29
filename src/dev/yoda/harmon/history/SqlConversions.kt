package dev.yoda.harmon.history

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
