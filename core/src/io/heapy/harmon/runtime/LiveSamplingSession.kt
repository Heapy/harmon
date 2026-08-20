package io.heapy.harmon.runtime

import io.heapy.harmon.analysis.AlertAnalyzer
import io.heapy.harmon.analysis.orphanAlertKey
import io.heapy.harmon.config.HarmonConfig
import io.heapy.harmon.model.Alert
import io.heapy.harmon.model.AlertCategory
import io.heapy.harmon.model.ProcessIdentity
import io.heapy.harmon.model.RawSystemSnapshot
import io.heapy.harmon.model.SystemUsage
import io.heapy.harmon.monitor.CollectionProfile
import io.heapy.harmon.monitor.SystemCollector
import io.heapy.harmon.monitor.UsageCalculator
import io.heapy.harmon.report.WebUiPayload
import io.heapy.harmon.report.WebUiPayloadFactory
import io.heapy.harmon.util.failureDescription
import kotlin.time.Clock
import kotlin.time.Instant

/** [config] supplies the notifier's thresholds; a null one leaves the view without alerts. */
class LiveSamplingSession(
    private val collector: SystemCollector,
    private val calculator: UsageCalculator,
    private val sampleSeconds: Long,
    private val now: () -> Instant = Clock.System::now,
    private val monotonicNowNanoseconds: () -> ULong,
    previousPayload: WebUiPayload? = null,
    private val config: HarmonConfig? = null,
    private val analyzer: AlertAnalyzer = AlertAnalyzer(),
) {
    private var previous: RawSystemSnapshot? = null
    private var sequence = 0uL
    private var lastGood = WebUiPayloadFactory.warming(
        sampleIntervalSeconds = sampleSeconds.toDouble(),
        previous = previousPayload,
    )
    private var staleSince: Instant? = null
    private var nextFullDeadlineNanoseconds: ULong? = null
    private var attributionState: AttributionState? = null
    private var attributionWarning: String? = null
    private var activeAlertKeys: Set<String> = emptySet()
    private var stickyOrphans: Map<String, Alert> = emptyMap()

    init {
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
    }

    fun warmingPayload(generatedAt: Instant = now()): WebUiPayload =
        WebUiPayloadFactory.warming(
            sampleIntervalSeconds = sampleSeconds.toDouble(),
            generatedAt = generatedAt,
            previous = lastGood,
        ).also { lastGood = it }

    fun capture(): WebUiPayload {
        val monotonicNow = monotonicNowNanoseconds()
        val profile = profileFor(monotonicNow)
        if (profile == CollectionProfile.FULL && previous != null) {
            nextFullDeadlineNanoseconds = monotonicNow.saturatingAdd(FULL_INTERVAL_NANOSECONDS)
        }

        val captured = try {
            collector.capture(profile)
        } catch (failure: Throwable) {
            return when {
                profile == CollectionProfile.FULL && previous == null -> warmingFailure(failure)
                profile == CollectionProfile.FULL -> captureFastAfterFullFailure(failure)
                else -> staleFailure(failure)
            }
        }

        if (profile == CollectionProfile.FULL) {
            cacheAttribution(captured)
            attributionWarning = null
            if (previous == null) {
                nextFullDeadlineNanoseconds = monotonicNow.saturatingAdd(FULL_INTERVAL_NANOSECONDS)
            }
        }
        return publish(captured.withCachedAttribution(), profile)
    }

    private fun captureFastAfterFullFailure(fullFailure: Throwable): WebUiPayload {
        attributionWarning = "FULL capture failed: ${failureDescription(fullFailure)}"
        val captured = try {
            collector.capture(CollectionProfile.LIVE_FAST)
        } catch (fastFailure: Throwable) {
            return staleFailure(
                IllegalStateException(
                    "${attributionWarning}; LIVE_FAST capture failed: ${failureDescription(fastFailure)}",
                ),
            )
        }
        return publish(captured.withCachedAttribution(), CollectionProfile.LIVE_FAST)
    }

    private fun publish(
        current: RawSystemSnapshot,
        appliedProfile: CollectionProfile,
    ): WebUiPayload {
        val baseline = previous
        if (baseline == null) {
            previous = current
            staleSince = null
            return WebUiPayloadFactory.warming(
                sampleIntervalSeconds = sampleSeconds.toDouble(),
                generatedAt = now(),
                previous = lastGood,
            ).also { lastGood = it }
        }

        return try {
            val usage = calculator.calculate(baseline, current)
            previous = current
            sequence = sequence.saturatingIncrement()
            val alerting = alertsFor(usage)
            WebUiPayloadFactory.live(
                usage = usage,
                sequence = sequence,
                attributionCapturedAt = attributionState?.capturedAt,
                attributionWarning = attributionWarning,
                appliedProfile = appliedProfile,
                alerts = alerting.alerts,
                suppressedAlertKeys = alerting.suppressedKeys,
                sampleIntervalSeconds = sampleSeconds.toDouble(),
                generatedAt = now(),
            ).also {
                lastGood = it
                staleSince = null
            }
        } catch (failure: Throwable) {
            staleFailure(failure)
        }
    }

    /** Replaying the previous firing keys applies the clear thresholds, so a badge cannot blink. */
    private fun alertsFor(usage: SystemUsage): LiveAlerts {
        val settings = config ?: return LiveAlerts(emptyList(), emptyList())
        val outcome = analyzer.analyze(usage, settings, activeAlertKeys)
        activeAlertKeys = outcome.firingKeys
        val orphans = carriedOrphans(usage, outcome.alerts)
        val fresh = outcome.alerts.filter { it.category != AlertCategory.ORPHAN }
        return LiveAlerts(
            alerts = fresh + orphans,
            suppressedKeys = outcome.suppressedKeys.toList().sorted(),
        )
    }

    /** Losing a parent lasts one sample, so the mark is carried while the process stays measured. */
    private fun carriedOrphans(usage: SystemUsage, alerts: List<Alert>): List<Alert> {
        val live = usage.processes.mapTo(mutableSetOf()) { orphanAlertKey(it.identity) }
        val carried = stickyOrphans.filterKeys { it in live }.toMutableMap()
        for (alert in alerts) {
            if (alert.category == AlertCategory.ORPHAN) carried[alert.key] = alert
        }
        stickyOrphans = carried
        return carried.entries.sortedBy { it.key }.map { it.value }
    }

    private data class LiveAlerts(
        val alerts: List<Alert>,
        val suppressedKeys: List<String>,
    )

    private fun warmingFailure(failure: Throwable): WebUiPayload =
        WebUiPayloadFactory.warming(
            sampleIntervalSeconds = sampleSeconds.toDouble(),
            generatedAt = now(),
            previous = lastGood,
            error = failureDescription(failure),
        ).also { lastGood = it }

    private fun staleFailure(failure: Throwable): WebUiPayload {
        val generatedAt = now()
        val since = staleSince ?: generatedAt.also { staleSince = it }
        return WebUiPayloadFactory.stale(
            lastGood = lastGood,
            error = failureDescription(failure),
            staleSince = since,
            retrySeconds = sampleSeconds.toDouble(),
            generatedAt = generatedAt,
        )
    }

    private fun profileFor(monotonicNow: ULong): CollectionProfile = when {
        previous == null -> CollectionProfile.FULL
        nextFullDeadlineNanoseconds?.let { monotonicNow >= it } == true -> CollectionProfile.FULL
        else -> CollectionProfile.LIVE_FAST
    }

    private fun cacheAttribution(snapshot: RawSystemSnapshot) {
        val attributionByIdentity = snapshot.processes.mapNotNull { process ->
            val compressed = process.compressedOrPagedOutBytes ?: return@mapNotNull null
            val regions = process.virtualMemoryRegionCount ?: return@mapNotNull null
            process.identity to Attribution(compressed, regions)
        }.toMap()
        attributionState = AttributionState(
            byIdentity = attributionByIdentity,
            compressedAttributionProcessCount = snapshot.compressedAttributionProcessCount,
            compressedAttributionFailureCount = snapshot.compressedAttributionFailureCount,
            capturedAt = snapshot.capturedAt,
        )
    }

    private fun RawSystemSnapshot.withCachedAttribution(): RawSystemSnapshot {
        val cached = attributionState ?: return this
        val attributedProcesses = processes.map { process ->
            val attribution = cached.byIdentity[process.identity]
            process.copy(
                compressedOrPagedOutBytes = attribution?.compressedOrPagedOutBytes,
                virtualMemoryRegionCount = attribution?.virtualMemoryRegionCount,
            )
        }
        return copy(
            processes = attributedProcesses,
            compressedAttributionProcessCount = cached.compressedAttributionProcessCount,
            compressedAttributionFailureCount = cached.compressedAttributionFailureCount,
        )
    }

    private data class AttributionState(
        val byIdentity: Map<ProcessIdentity, Attribution>,
        val compressedAttributionProcessCount: Int,
        val compressedAttributionFailureCount: Int,
        val capturedAt: Instant,
    )

    private data class Attribution(
        val compressedOrPagedOutBytes: ULong,
        val virtualMemoryRegionCount: Int,
    )

    private companion object {
        const val FULL_INTERVAL_NANOSECONDS = 30_000_000_000uL
    }
}

private fun ULong.saturatingAdd(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

private fun ULong.saturatingIncrement(): ULong =
    if (this == ULong.MAX_VALUE) this else this + 1u
