package dev.yoda.harmon.runtime

import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import dev.yoda.harmon.history.History
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.CollectionProfile
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

/** Bounds how long Notification Center sources can go without a run-loop turn. */
const val MAX_SLEEP_SLICE_MILLISECONDS = 30_000uL

fun sleepSliceMillis(remainingNs: ULong): ULong {
    val requested = maxOf(remainingNs / NANOS_PER_MILLISECOND, 1uL)
    return minOf(requested, MAX_SLEEP_SLICE_MILLISECONDS)
}

/**
 * Services the CoreFoundation run loop when notifications are enabled. A non-timeout return did
 * not consume the slice, so the remainder is parked rather than busy-polled.
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
    private val collector: SystemCollector,
    private val notifications: Lazy<NotificationDispatcher>,
    private val calculator: UsageCalculator = UsageCalculator(config.terminalApplications),
    private val analyzer: AlertAnalyzer = AlertAnalyzer(),
    private val log: (String) -> Unit = ::println,
    private val logError: (String) -> Unit = ::printError,
    private val history: History? = null,
) {
    private val alertState = AlertState(restored = restorableStateOrNull())

    /** Coalesces a persistent database failure to one log entry until a write succeeds. */
    private var historyWriteFailing = false

    fun runForever(): Nothing {
        log("${Clock.System.now()} Harmon started; interval=${config.intervalSeconds}s")
        var previous = captureWithRetry()

        while (true) {
            sleepSeconds(config.intervalSeconds)
            previous = runCycle(previous)
        }
    }

    /** Capture failures keep the old baseline; handling failures advance it to avoid replay loops. */
    fun runCycle(previous: RawSystemSnapshot): RawSystemSnapshot {
        val current = try {
            collector.capture(CollectionProfile.FULL)
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
     * Commits alert state in `finally` so render or delivery failures cannot strand cleared keys.
     * History is written after that commit, making persisted state the next sample's starting state.
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
                            "retrying it in $delaySamples samples if it is still firing then",
                    )
                }
            recordSafely(sampled.report, outcome.results)
        }
    }

    fun sampleOnce(sampleSeconds: Long = config.onceSampleSeconds): MonitoringReport {
        require(sampleSeconds in SAMPLE_SECONDS_RANGE) {
            "sampleSeconds must be between ${SAMPLE_SECONDS_RANGE.first} " +
                "and ${SAMPLE_SECONDS_RANGE.last}, got $sampleSeconds"
        }
        val previous = collector.capture(CollectionProfile.FULL)
        sleepSeconds(sampleSeconds)
        val current = collector.capture(CollectionProfile.FULL)
        return createSample(previous, current).report
    }

    fun testNotifications(): List<DeliveryResult> =
        notifications.value.deliver(ReportFormatter.testPayload()).results

    fun deliver(report: MonitoringReport, reportText: String): List<DeliveryResult> =
        notifications.value.deliver(
            ReportFormatter.notification(report, reportText = reportText),
        ).results

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
                return collector.capture(CollectionProfile.FULL)
            } catch (failure: Throwable) {
                logFailure("initial collection failed", failure, "; retrying in 10s")
                sleepSeconds(INITIAL_RETRY_SECONDS)
            }
        }
    }

    private fun deliverSafely(report: MonitoringReport, reportText: String): DeliveryOutcome =
        try {
            deliverSample(report, reportText)
        } catch (failure: Throwable) {
            logFailure("notification delivery failed", failure)
            DeliveryOutcome.NONE
        }

    /** A corrupt history read must not turn launchd restart into a monitoring boot loop. */
    private fun restorableStateOrNull(): AlertStateSnapshot? = try {
        history?.restorableAlertState()
    } catch (failure: Throwable) {
        logFailure("history restore failed", failure, "; starting from a clean alert state")
        null
    }

    /** History failures, including a full disk, never stop monitoring. */
    private fun recordSafely(report: MonitoringReport, deliveries: List<DeliveryResult>) {
        val store = history ?: return
        try {
            store.record(report, deliveries, alertState.snapshot())
            historyWriteFailing = false
        } catch (failure: Throwable) {
            if (!historyWriteFailing) {
                logFailure("history write failed", failure)
                historyWriteFailing = true
            }
        }
    }

    private fun deliverSample(report: MonitoringReport, reportText: String): DeliveryOutcome {
        val plan = pushPlan(report) ?: return DeliveryOutcome.NONE
        // Touch the lazy dispatcher only after a push is planned; constructing it boots AppKit.
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
        val outcome = when {
            summary.decisiveSuccess -> DeliveryOutcome(delivered = pushed, failed = emptySet())
            plan.recordsFailures -> DeliveryOutcome(delivered = emptySet(), failed = pushed)
            else -> DeliveryOutcome.NONE
        }
        return outcome.copy(results = summary.results)
    }

    /** Every-sample mode carries unsettled keys but never creates retry backoff. */
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

    /** Uses a monotonic deadline so wall-clock changes cannot stretch or collapse the interval. */
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

    private data class SampledAlerts(
        val report: MonitoringReport,
        val firingKeys: Set<String>,
    )

    private data class PushPlan(
        val highlighted: List<Alert>,
        val newAlertKeys: List<String>,
        val recordsFailures: Boolean,
    )

    private data class DeliveryOutcome(
        val delivered: Set<String>,
        val failed: Set<String>,
        val results: List<DeliveryResult> = emptyList(),
    ) {
        companion object {
            val NONE = DeliveryOutcome(delivered = emptySet(), failed = emptySet())
        }
    }

    private companion object {
        const val INITIAL_RETRY_SECONDS = 10L
    }
}
