package dev.yoda.harmon.history

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How much time the retention pass tries to leave between two of its own runs. */
const val PRUNE_PERIOD_SECONDS = 3_600L

/**
 * Whether the sample numbered [sampleIndex] — counted from zero within this run of the agent — is
 * the one that carries the retention pass.
 *
 * The schedule is expressed in samples because that is the only clock the write path has, and it is
 * derived from [PRUNE_PERIOD_SECONDS] rather than fixed at a sample count: at the default interval
 * of 300 seconds every twelfth sample prunes, at 30 seconds every hundred and twentieth. The
 * division rounds up, so the gap never falls below the period and never exceeds it by more than one
 * interval — and an interval longer than an hour lands on one sample per pass instead of on zero,
 * which is what a rounding-down division would produce.
 *
 * Sample zero prunes, which is what gives the plan its start-up pass: an agent that was down for a
 * week comes back to a full window and clears it before it starts adding to it.
 */
fun shouldPrune(sampleIndex: Long, intervalSeconds: Long): Boolean {
    val interval = intervalSeconds.coerceAtLeast(1L)
    val samplesPerPass = (PRUNE_PERIOD_SECONDS + interval - 1) / interval
    return sampleIndex % samplesPerPass == 0L
}

/**
 * The oldest `captured_at` history keeps, [retentionDays] before [now], in the fixed-width form the
 * column stores.
 *
 * A string rather than an `Instant` because that is what the comparison behind retention is: the
 * delete is `captured_at < cutoff`, so the cutoff has to be truncated to whole seconds exactly the
 * way the stored values are, or it would sort against a fraction that is not there. Being a `<`, a
 * sample landing exactly on the cutoff survives one more pass.
 */
fun retentionCutoff(now: Instant, retentionDays: Long): String =
    (now - retentionDays.days).toSqlTimestamp()
