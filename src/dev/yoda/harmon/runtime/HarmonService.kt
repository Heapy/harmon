package dev.yoda.harmon.runtime

import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.analysis.AlertCooldown
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.model.MonitoringReport
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
    private val notifications: NotificationDispatcher =
        NotificationDispatcher.from(config.notifications),
    private val log: (String) -> Unit = ::println,
    private val logError: (String) -> Unit = ::printError,
) {
    private val cooldown = AlertCooldown(config.alertCooldownSeconds)

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

            val report = createReport(previous, current)
            previous = current
            log(ReportFormatter.text(report))
            deliverIfNeeded(report)
        }
    }

    fun sampleOnce(sampleSeconds: Long = config.onceSampleSeconds): MonitoringReport {
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
        val previous = collector.capture()
        sleepSeconds(sampleSeconds)
        val current = collector.capture()
        return createReport(previous, current)
    }

    fun testNotifications() =
        notifications.deliver(ReportFormatter.testPayload())

    fun deliver(report: MonitoringReport) =
        notifications.deliver(ReportFormatter.notification(report))

    private fun createReport(
        previous: dev.yoda.harmon.model.RawSystemSnapshot,
        current: dev.yoda.harmon.model.RawSystemSnapshot,
    ): MonitoringReport {
        val usage = calculator.calculate(previous, current)
        return MonitoringReport(
            usage = usage,
            alerts = analyzer.analyze(usage, config),
            topProcessCount = config.topProcessCount,
        )
    }

    private fun captureWithRetry(): dev.yoda.harmon.model.RawSystemSnapshot {
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

    private fun deliverIfNeeded(report: MonitoringReport) {
        val freshAlerts = cooldown.newAlerts(report.alerts)
        val shouldDeliver = config.notifications.notifyEverySample || freshAlerts.isNotEmpty()
        if (!shouldDeliver || notifications.isEmpty) {
            return
        }

        val outboundReport = report.copy(
            alerts = if (config.notifications.notifyEverySample) report.alerts else freshAlerts,
        )
        val results = notifications.deliver(ReportFormatter.notification(outboundReport))
        val anySuccess = results.any { it.successful }
        results.forEach { result ->
            val stream = if (result.successful) log else logError
            stream(
                "${Clock.System.now()} notification ${result.channel}: ${result.detail}",
            )
        }
        if (anySuccess) {
            cooldown.markDelivered(freshAlerts)
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
