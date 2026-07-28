package dev.yoda.harmon.runtime

import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.MAX_DELIVERY_ATTEMPTS
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import dev.yoda.harmon.ipc.CollectorClient
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
 * zero-length wait. Public so the arithmetic can be tested without sleeping.
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
 * slice rather than latched off after the first probe that finds no sources: the dispatcher is
 * built lazily, so AppKit and its run loop sources only exist after the first delivery, and a
 * latch would leave the notification click dead for the rest of the process lifetime.
 *
 * Only a run that times out actually consumed the slice. Every other return — no sources at all,
 * or a run loop somebody stopped — comes back immediately, so the rest of the slice is spent
 * parked instead; re-entering the run loop there would busy-poll for the whole interval. Public
 * so the run loop branch can be exercised with a millisecond-long slice.
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
     * A failed capture leaves [previous] in place, so the next cycle still has a window to diff
     * against; a successful one advances it before the sample is handled, so a pair that blows up
     * is not replayed against every following capture. Both failures are logged and swallowed —
     * the agent is a daemon and must survive them. Public so the failure paths can be driven from
     * a test without an endless loop.
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
     * One iteration of the monitoring loop, without the sleeping and capturing around it. Public
     * so tests can drive a sequence of samples directly.
     *
     * The alert state is committed for every report that could be built, the samples without a
     * delivery included: a key that stopped firing has to leave the settled set, otherwise its
     * next appearance would be mistaken for a repeat and never pushed. That is why the commit
     * sits in a `finally` — a render or a delivery that throws must not strand the state on the
     * sample before it. A sample that cannot be turned into a report at all commits nothing;
     * there are no alerts to commit.
     */
    fun handleSample(previous: RawSystemSnapshot, current: RawSystemSnapshot) {
        val report = createReport(previous, current)
        var outcome = DeliveryOutcome.NONE
        try {
            val reportText = ReportFormatter.text(report)
            log(reportText)
            outcome = deliverSafely(report, reportText)
        } finally {
            alertState
                .commit(report.alerts, outcome.delivered, outcome.failed)
                .forEach { key ->
                    logError(
                        "${Clock.System.now()} giving up on alert $key after " +
                            "$MAX_DELIVERY_ATTEMPTS failed deliveries; it is pushed again " +
                            "once it clears and fires anew",
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
        return createReport(previous, current)
    }

    fun testNotifications() =
        notifications.value.deliver(ReportFormatter.testPayload())

    /**
     * Pushes [report] as a single one-off notification. [reportText] is accepted already rendered
     * so a caller that also prints the report does not render it twice.
     */
    fun deliver(
        report: MonitoringReport,
        reportText: String = ReportFormatter.text(report),
    ) = notifications.value.deliver(
        ReportFormatter.notification(report, reportText = reportText),
    )

    private fun createReport(
        previous: RawSystemSnapshot,
        current: RawSystemSnapshot,
    ): MonitoringReport {
        val usage = calculator.calculate(previous, current)
        return MonitoringReport(
            usage = usage,
            alerts = analyzer.analyze(usage, config, alertState.activeKeys),
            topProcessCount = config.topProcessCount,
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
     * Pushes the alerts that crossed their threshold on this sample and reports which keys were
     * pushed and whether the push was confirmed.
     *
     * The dispatcher is only touched once this sample is known to need a push. Reading it earlier
     * — to check [NotificationDispatcher.isEmpty], say — would build the system channel and boot
     * AppKit on every quiet sample, which is exactly what the lazy holder exists to avoid.
     *
     * `notifyEverySample` widens what the push carries, not what counts as new: the payload still
     * names only the keys that crossed their threshold on this sample, so a consumer can tell a
     * fresh alert from one that has been firing for an hour.
     */
    private fun deliverSample(report: MonitoringReport, reportText: String): DeliveryOutcome {
        val everySample = config.notifications.notifyEverySample
        val freshAlerts = alertState.newlyActive(report.alerts)
        if (!everySample && freshAlerts.isEmpty()) {
            return DeliveryOutcome.NONE
        }

        val dispatcher = notifications.value
        if (dispatcher.isEmpty) {
            return DeliveryOutcome.NONE
        }

        val highlighted = if (everySample) report.alerts else freshAlerts
        val results = dispatcher.deliver(
            ReportFormatter.notification(
                report = report,
                highlighted = highlighted,
                newAlertKeys = freshAlerts.map { it.key },
                reportText = reportText,
            ),
        )
        results.forEach { result ->
            val stream = if (result.successful) log else logError
            stream(
                "${Clock.System.now()} notification ${result.channel}: ${result.detail}",
            )
        }
        val pushed = highlighted.mapTo(mutableSetOf()) { it.key }
        return if (dispatcher.decisiveSuccess(results)) {
            DeliveryOutcome(delivered = pushed, failed = emptySet())
        } else {
            DeliveryOutcome(delivered = emptySet(), failed = pushed)
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
