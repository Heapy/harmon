package dev.yoda.harmon.runtime

import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.report.ReportFormatter
import dev.yoda.harmon.util.failureDescription
import dev.yoda.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreFoundation.kCFRunLoopRunTimedOut
import platform.posix.usleep
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val NANOS_PER_MILLISECOND = 1_000_000uL
private const val MICROS_PER_MILLISECOND = 1_000uL
private const val MILLIS_PER_SECOND = 1_000.0

/** Longest single stretch the agent parks for before it re-checks its deadline. */
const val MAX_SLEEP_SLICE_MILLISECONDS = 30_000uL

/**
 * Length of the next sleep slice, in milliseconds, for a sleep with [remainingNs] left to run.
 *
 * A slice never overshoots the deadline and never rounds a non-zero remainder down to a busy
 * zero-length wait.
 */
fun sleepSliceMillis(remainingNs: ULong): ULong {
    val requested = maxOf(remainingNs / NANOS_PER_MILLISECOND, 1uL)
    return minOf(requested, MAX_SLEEP_SLICE_MILLISECONDS)
}

/**
 * Spends one sleep slice of [sliceMs] milliseconds.
 *
 * With [systemNotifications] on, the slice is spent inside the CoreFoundation run loop, which is
 * what makes "Open report" on a delivered notification work. The run loop is re-probed on every
 * slice rather than latched off after a probe that finds no sources: the dispatcher is built
 * lazily, so its sources only exist after the first delivery, and a latch would leave the
 * notification click dead for the rest of the process lifetime.
 *
 * Only a run that times out consumed the slice; every other return comes back immediately, so the
 * rest of it is spent parked instead of busy-polling the run loop.
 */
@OptIn(ExperimentalForeignApi::class)
fun spendSleepSlice(sliceMs: ULong, systemNotifications: Boolean) {
    if (systemNotifications) {
        val result = CFRunLoopRunInMode(
            mode = kCFRunLoopDefaultMode,
            seconds = sliceMs.toDouble() / MILLIS_PER_SECOND,
            returnAfterSourceHandled = false,
        )
        if (result == kCFRunLoopRunTimedOut) {
            return
        }
    }
    usleep((sliceMs * MICROS_PER_MILLISECOND).toUInt())
}

class HarmonService(
    private val config: HarmonConfig,
    private val collector: SystemCollector = CollectorClient(config.collectorSocket),
    private val calculator: UsageCalculator = UsageCalculator(config.terminalApplications),
    private val analyzer: AlertAnalyzer = AlertAnalyzer(),
    private val notifications: Lazy<NotificationDispatcher> =
        lazy { NotificationDispatcher.from(config.notifications) },
    private val log: (String) -> Unit = ::println,
    private val logError: (String) -> Unit = ::printError,
) {
    private val alertState = AlertState()

    fun runForever(): Nothing {
        log("${Clock.System.now()} Harmon started; interval=${config.intervalSeconds}s")
        var previous = captureWithRetry()

        while (true) {
            sleepSeconds(config.intervalSeconds)
            previous = runCycle(previous)
        }
    }

    /**
     * One capture-and-handle cycle of the monitoring loop, without the sleep around it, returning
     * the snapshot the next cycle has to diff against.
     *
     * A failed capture leaves [previous] in place so the next cycle still has a window to diff
     * against; a successful one advances it before the sample is handled, so a pair that blows up
     * is not replayed forever. Both failures are logged and swallowed: this is a daemon.
     */
    fun runCycle(previous: RawSystemSnapshot): RawSystemSnapshot {
        val current = try {
            collector.capture()
        } catch (failure: Throwable) {
            logFailure("collection failed", failure)
            return previous
        }
        try {
            handleSample(previous, current)
        } catch (failure: Throwable) {
            logFailure("sample handling failed", failure)
        }
        return current
    }

    /**
     * One iteration of the monitoring loop, without the sleeping and capturing around it.
     *
     * The commit sits in a `finally` because it has to happen for every report that could be
     * built, deliveries and renders that throw included: a key that stopped firing has to leave
     * the settled set, or its next appearance is mistaken for a repeat and never pushed.
     */
    fun handleSample(previous: RawSystemSnapshot, current: RawSystemSnapshot) {
        val sampled = createSample(previous, current)
        var outcome = DeliveryOutcome.NONE
        try {
            val reportText = ReportFormatter.text(sampled.report)
            log(reportText)
            outcome = deliverSafely(sampled.report, reportText)
        } finally {
            alertState
                .commit(sampled.firingKeys, outcome.delivered, outcome.failed)
                .forEach { (key, delaySamples) ->
                    logError(
                        "${Clock.System.now()} delivery of alert $key keeps failing; " +
                            "retrying it in $delaySamples samples",
                    )
                }
        }
    }

    fun sampleOnce(sampleSeconds: Long = config.onceSampleSeconds): MonitoringReport {
        require(sampleSeconds in SAMPLE_SECONDS_RANGE) {
            "sampleSeconds must be between ${SAMPLE_SECONDS_RANGE.first} " +
                "and ${SAMPLE_SECONDS_RANGE.last}, got $sampleSeconds"
        }
        val previous = collector.capture()
        sleepSeconds(sampleSeconds)
        val current = collector.capture()
        return createSample(previous, current).report
    }

    fun testNotifications(): List<DeliveryResult> =
        notifications.value.deliver(ReportFormatter.testPayload()).results

    /**
     * Pushes [report] as a single one-off notification. [reportText] is accepted already rendered
     * so a caller that also prints the report does not render it twice.
     */
    fun deliver(report: MonitoringReport, reportText: String): List<DeliveryResult> =
        notifications.value.deliver(
            ReportFormatter.notification(report, reportText = reportText),
        ).results

    /**
     * The report to publish, paired with the keys the alert state has to remember.
     *
     * The two differ: an already-alerting key the per-category cap pushed out of the report is
     * still firing and forgetting it would make its return look like a fresh alert, while a key
     * that first crossed its threshold below the cut was never pushed and must not enter the
     * state, where the lowered clear threshold would keep it alerting long after it settled back.
     */
    private fun createSample(
        previous: RawSystemSnapshot,
        current: RawSystemSnapshot,
    ): SampledAlerts {
        val usage = calculator.calculate(previous, current)
        val outcome = analyzer.analyze(usage, config, alertState.activeKeys)
        return SampledAlerts(
            report = MonitoringReport(
                usage = usage,
                alerts = outcome.alerts,
                topProcessCount = config.topProcessCount,
                suppressedAlertKeys = outcome.suppressedKeys.sorted(),
            ),
            firingKeys = outcome.firingKeys,
        )
    }

    private fun captureWithRetry(): RawSystemSnapshot {
        while (true) {
            try {
                return collector.capture()
            } catch (failure: Throwable) {
                logFailure("initial collection failed", failure, "; retrying in 10s")
                sleepSeconds(INITIAL_RETRY_SECONDS)
            }
        }
    }

    /**
     * A delivery that throws — building the dispatcher boots AppKit, and that can fail — is
     * reported and treated as delivering nothing. Letting it escape would skip the commit in
     * [handleSample] and strand the alert state on the sample before it.
     */
    private fun deliverSafely(report: MonitoringReport, reportText: String): DeliveryOutcome =
        try {
            deliverSample(report, reportText)
        } catch (failure: Throwable) {
            logFailure("notification delivery failed", failure)
            DeliveryOutcome.NONE
        }

    /**
     * Pushes what [pushPlan] decided this sample is worth pushing and reports which keys were
     * carried and whether the push was confirmed.
     *
     * The dispatcher is only touched once this sample is known to need a push. Reading it
     * earlier — to check [NotificationDispatcher.isEmpty], say — would build the system channel
     * and boot AppKit on every quiet sample, which is what the lazy holder exists to avoid.
     */
    private fun deliverSample(report: MonitoringReport, reportText: String): DeliveryOutcome {
        val plan = pushPlan(report) ?: return DeliveryOutcome.NONE
        val dispatcher = notifications.value
        if (dispatcher.isEmpty) {
            return DeliveryOutcome.NONE
        }

        val summary = dispatcher.deliver(
            ReportFormatter.notification(
                report = report,
                highlighted = plan.highlighted,
                newAlertKeys = plan.newAlertKeys,
                reportText = reportText,
            ),
        )
        summary.results.forEach { result ->
            val stream = if (result.successful) log else logError
            stream(
                "${Clock.System.now()} notification ${result.channel}: ${result.detail}",
            )
        }
        val pushed = plan.highlighted.mapTo(mutableSetOf()) { it.key }
        return when {
            summary.decisiveSuccess -> DeliveryOutcome(delivered = pushed, failed = emptySet())
            plan.recordsFailures -> DeliveryOutcome(delivered = emptySet(), failed = pushed)
            else -> DeliveryOutcome.NONE
        }
    }

    /**
     * What this sample should push, or null when it should push nothing.
     *
     * `notifyEverySample` widens what the push carries, not what counts as new. It pushes whether
     * or not the backoff defers a key, so it reads the unsettled keys rather than the pushable
     * ones: a deferred key rides in the payload, and leaving it out of the new set would hide it
     * from the consumer for its whole firing episode. For the same reason it records no failures:
     * nothing can be deferred in a mode that pushes regardless.
     */
    private fun pushPlan(report: MonitoringReport): PushPlan? {
        if (config.notifications.notifyEverySample) {
            return PushPlan(
                highlighted = report.alerts,
                newAlertKeys = alertState.unsettled(report.alerts).map { it.key },
                recordsFailures = false,
            )
        }
        val fresh = alertState.newlyActive(report.alerts)
        return if (fresh.isEmpty()) {
            null
        } else {
            PushPlan(
                highlighted = fresh,
                newAlertKeys = fresh.map { it.key },
                recordsFailures = true,
            )
        }
    }

    private fun logFailure(context: String, failure: Throwable, suffix: String = "") {
        logError("${Clock.System.now()} $context: ${failureDescription(failure)}$suffix")
    }

    /**
     * Sleeps until [seconds] have passed on the monotonic clock, so a wall-clock adjustment cannot
     * stretch or collapse the interval. The thread is parked for whole slices instead of polling
     * the clock, which is what keeps an idle agent off the CPU.
     */
    private fun sleepSeconds(seconds: Long) {
        if (seconds <= 0L) {
            return
        }
        val started = TimeSource.Monotonic.markNow()
        val total = seconds.seconds
        while (true) {
            val remaining = total - started.elapsedNow()
            if (remaining <= Duration.ZERO) {
                return
            }
            spendSleepSlice(
                sliceMs = sleepSliceMillis(remaining.inWholeNanoseconds.toULong()),
                systemNotifications = config.notifications.systemEnabled,
            )
        }
    }

    /** One sample's publishable report and the complete key set behind it. */
    private data class SampledAlerts(
        val report: MonitoringReport,
        val firingKeys: Set<String>,
    )

    /** What one sample pushes: the alerts in front of the user, and which of them are new. */
    private data class PushPlan(
        val highlighted: List<Alert>,
        val newAlertKeys: List<String>,
        /** Whether a failed push defers these keys; the every-sample mode never defers. */
        val recordsFailures: Boolean,
    )

    /** What a single sample's push achieved: the keys it carried, and whether they landed. */
    private data class DeliveryOutcome(
        val delivered: Set<String>,
        val failed: Set<String>,
    ) {
        companion object {
            val NONE = DeliveryOutcome(delivered = emptySet(), failed = emptySet())
        }
    }

    private companion object {
        const val INITIAL_RETRY_SECONDS = 10L
    }
}
