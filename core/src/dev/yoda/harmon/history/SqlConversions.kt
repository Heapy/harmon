package dev.yoda.harmon.history

import kotlin.time.Instant

/**
 * Fixed-width whole-second UTC keeps lexical ordering correct; variable fractional precision can
 * sort a later instant before an earlier whole-second value.
 */
fun Instant.toSqlTimestamp(): String = Instant.fromEpochSeconds(epochSeconds).toString()

/** SQLite has no unsigned integer, so values past the signed boundary clamp instead of turning negative. */
fun ULong.toSqlLong(): Long = coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
