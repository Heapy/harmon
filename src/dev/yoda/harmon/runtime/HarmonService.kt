package dev.yoda.harmon.runtime

import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.report.ReportFormatter
import dev.yoda.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreFoundation.kCFRunLoopRunFinished
import platform.posix.usleep
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val NANOS_PER_MILLISECOND = 1_000_000uL
private const val MICROS_PER_MILLISECOND = 1_000uL
private const val MILLIS_PER_SECOND = 1_000.0

/**
 * Length of the next sleep slice, in milliseconds, for a sleep with [remainingNs] left to run.
 *
 * A slice never overshoots the deadline and never rounds a non-zero remainder down to a busy
 * zero-length wait. Public so the arithmetic can be tested without sleeping.
 */
fun sleepSliceMillis(remainingNs: ULong, maxSliceMs: ULong): ULong {
    if (remainingNs == 0uL) {
        return 0uL
    }
    val requested = maxOf(remainingNs / NANOS_PER_MILLISECOND, 1uL)
    return minOf(requested, maxSliceMs)
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
            val current = try {
                collector.capture()
            } catch (failure: Throwable) {
                logError(
                    "${Clock.System.now()} collection failed: " +
                        (failure.message ?: failure::class.simpleName),
                )
                continue
            }

            // The window advances before the sample is handled, so a pair that blows up is not
            // replayed against every following capture.
            val sampleStart = previous
            previous = current
            try {
                handleSample(sampleStart, current)
            } catch (failure: Throwable) {
                logError(
                    "${Clock.System.now()} sample handling failed: " +
                        (failure.message ?: failure::class.simpleName),
                )
            }
        }
    }

    /**
     * One iteration of the monitoring loop, without the sleeping and capturing around it. Public
     * so tests can drive a sequence of samples directly.
     */
    fun handleSample(previous: RawSystemSnapshot, current: RawSystemSnapshot) {
        val report = createReport(previous, current)
        val reportText = ReportFormatter.text(report)
        log(reportText)
        deliverIfNeeded(report, reportText)
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

    fun deliver(report: MonitoringReport) =
        notifications.value.deliver(ReportFormatter.notification(report))

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
                logError(
                    "${Clock.System.now()} initial collection failed: " +
                        (failure.message ?: failure::class.simpleName) +
                        "; retrying in 10s",
                )
                sleepSeconds(INITIAL_RETRY_SECONDS)
            }
        }
    }

    /**
     * The state is committed on every sample, the samples without a delivery included: a key that
     * stopped firing has to leave the delivered set, otherwise its next appearance would be
     * mistaken for a repeat and never pushed.
     *
     * A delivery that throws — building the dispatcher boots AppKit, and that can fail — is
     * reported and treated as delivering nothing. Letting it escape would skip the commit and
     * strand the state on the sample before it.
     */
    private fun deliverIfNeeded(report: MonitoringReport, reportText: String) {
        val delivered = try {
            deliverSample(report, reportText)
        } catch (failure: Throwable) {
            logError(
                "${Clock.System.now()} notification delivery failed: " +
                    (failure.message ?: failure::class.simpleName),
            )
            emptySet()
        }
        alertState.commit(report.alerts, delivered)
    }

    /**
     * Pushes the alerts that crossed their threshold on this sample and returns the keys whose
     * delivery is confirmed.
     *
     * The dispatcher is only touched once this sample is known to need a push. Reading it earlier
     * — to check [NotificationDispatcher.isEmpty], say — would build the system channel and boot
     * AppKit on every quiet sample, which is exactly what the lazy holder exists to avoid.
     */
    private fun deliverSample(report: MonitoringReport, reportText: String): Set<String> {
        val everySample = config.notifications.notifyEverySample
        val freshAlerts = alertState.newlyActive(report.alerts)
        if (!everySample && freshAlerts.isEmpty()) {
            return emptySet()
        }

        val dispatcher = notifications.value
        if (dispatcher.isEmpty) {
            return emptySet()
        }

        val highlighted = if (everySample) report.alerts else freshAlerts
        val results = dispatcher.deliver(
            ReportFormatter.notification(report, highlighted, reportText),
        )
        results.forEach { result ->
            val stream = if (result.successful) log else logError
            stream(
                "${Clock.System.now()} notification ${result.channel}: ${result.detail}",
            )
        }
        return if (dispatcher.decisiveSuccess(results)) {
            highlighted.mapTo(mutableSetOf()) { it.key }
        } else {
            emptySet()
        }
    }

    /**
     * Sleeps until [seconds] have passed on the monotonic clock, so a wall-clock adjustment cannot
     * stretch or collapse the interval. The thread is parked for the whole slice instead of
     * polling the clock, which is what keeps an idle agent off the CPU.
     *
     * With system notifications on, each slice is spent inside the run loop, which is what makes
     * "Open report" on a delivered notification work. The run loop is re-probed on every slice
     * rather than latched off after the first [kCFRunLoopRunFinished]: the dispatcher is built
     * lazily, so AppKit and its run loop sources only exist after the first delivery, and a latch
     * would leave the notification click dead for the rest of the process lifetime. A probe with
     * no sources returns immediately, and the slice is then spent parked instead.
     */
    @OptIn(ExperimentalForeignApi::class)
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
            val sliceMs = sleepSliceMillis(
                remainingNs = remaining.inWholeNanoseconds.toULong(),
                maxSliceMs = MAX_SLEEP_SLICE_MILLISECONDS,
            )
            if (config.notifications.systemEnabled) {
                val result = CFRunLoopRunInMode(
                    mode = kCFRunLoopDefaultMode,
                    seconds = sliceMs.toDouble() / MILLIS_PER_SECOND,
                    returnAfterSourceHandled = false,
                )
                if (result != kCFRunLoopRunFinished) {
                    continue
                }
            }
            usleep((sliceMs * MICROS_PER_MILLISECOND).toUInt())
        }
    }

    private companion object {
        const val INITIAL_RETRY_SECONDS = 10L
        const val MAX_SLEEP_SLICE_MILLISECONDS = 30_000uL
    }
}
