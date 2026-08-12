package dev.yoda.harmon.runtime

import dev.yoda.harmon.model.RawSystemSnapshot
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
) {
    private var previous: RawSystemSnapshot? = null
    private var sequence = 0uL
    private var lastGood = WebUiPayloadFactory.warming(sampleSeconds.toDouble())
    private var staleSince: Instant? = null

    init {
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
    }

    fun capture(): WebUiPayload = try {
        val current = collector.capture()
        val baseline = previous
        if (baseline == null) {
            previous = current
            staleSince = null
            WebUiPayloadFactory.warming(
                sampleIntervalSeconds = sampleSeconds.toDouble(),
                generatedAt = now(),
            ).also { lastGood = it }
        } else {
            val usage = calculator.calculate(baseline, current)
            previous = current
            sequence = if (sequence == ULong.MAX_VALUE) sequence else sequence + 1u
            WebUiPayloadFactory.live(
                usage = usage,
                sequence = sequence,
                sampleIntervalSeconds = sampleSeconds.toDouble(),
                generatedAt = now(),
            ).also {
                lastGood = it
                staleSince = null
            }
        }
    } catch (failure: Throwable) {
        val generatedAt = now()
        val since = staleSince ?: generatedAt.also { staleSince = it }
        WebUiPayloadFactory.stale(
            lastGood = lastGood,
            error = failureDescription(failure),
            staleSince = since,
            retrySeconds = sampleSeconds.toDouble(),
            generatedAt = generatedAt,
        )
    }
}
