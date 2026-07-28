package dev.yoda.harmon.runtime

import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.nativebridge.hm_sleep_millis
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.report.ReportFormatter
import dev.yoda.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreFoundation.kCFRunLoopRunFinished
import kotlin.time.Clock

class HarmonService(
    private val config: HarmonConfig,
    private val collector: SystemCollector = CollectorClient(config.collectorSocket),
    private val calculator: UsageCalculator = UsageCalculator(),
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

            val sampleStart = previous
            previous = current
            handleSample(sampleStart, current)
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
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
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
     */
    private fun deliverIfNeeded(report: MonitoringReport, reportText: String) {
        alertState.commit(report.alerts, deliverSample(report, reportText))
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

    @OptIn(ExperimentalForeignApi::class)
    private fun sleepSeconds(seconds: Long) {
        val deadline = CFAbsoluteTimeGetCurrent() + seconds.toDouble()
        while (CFAbsoluteTimeGetCurrent() < deadline) {
            val remaining = deadline - CFAbsoluteTimeGetCurrent()
            val result = CFRunLoopRunInMode(
                mode = kCFRunLoopDefaultMode,
                seconds = minOf(remaining, RUN_LOOP_SLICE_SECONDS),
                returnAfterSourceHandled = true,
            )
            if (result == kCFRunLoopRunFinished) {
                hm_sleep_millis(RUN_LOOP_IDLE_MILLISECONDS)
            }
        }
    }

    private companion object {
        const val INITIAL_RETRY_SECONDS = 10L
        const val RUN_LOOP_SLICE_SECONDS = 1.0
        const val RUN_LOOP_IDLE_MILLISECONDS = 10uL
    }
}
