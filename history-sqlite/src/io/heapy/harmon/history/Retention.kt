package io.heapy.harmon.history

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

const val PRUNE_PERIOD_SECONDS = 3_600L

/**
 * Rounds the period up to whole samples so pruning never runs too often. Sample zero performs the
 * startup pass; intervals longer than the period still prune once per sample.
 */
fun shouldPrune(sampleIndex: Long, intervalSeconds: Long): Boolean {
    val interval = intervalSeconds.coerceAtLeast(1L)
    val samplesPerPass = (PRUNE_PERIOD_SECONDS + interval - 1) / interval
    return sampleIndex % samplesPerPass == 0L
}

/** Uses the same whole-second representation as captured_at so lexical comparison stays exact. */
fun retentionCutoff(now: Instant, retentionDays: Long): String =
    (now - retentionDays.days).toSqlTimestamp()
