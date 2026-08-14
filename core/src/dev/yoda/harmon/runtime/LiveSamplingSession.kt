package dev.yoda.harmon.runtime

import dev.yoda.harmon.model.ProcessIdentity
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.CollectionProfile
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiPayload
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.util.failureDescription
import kotlin.time.Clock
import kotlin.time.Instant

class LiveSamplingSession(
    private val collector: SystemCollector,
    private val calculator: UsageCalculator,
    private val sampleSeconds: Long,
    private val now: () -> Instant = Clock.System::now,
    private val monotonicNowNanoseconds: () -> ULong,
    previousPayload: WebUiPayload? = null,
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
            WebUiPayloadFactory.live(
                usage = usage,
                sequence = sequence,
                attributionCapturedAt = attributionState?.capturedAt,
                attributionWarning = attributionWarning,
                appliedProfile = appliedProfile,
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
