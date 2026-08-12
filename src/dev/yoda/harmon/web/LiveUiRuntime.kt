package dev.yoda.harmon.web

import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiPayload
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.report.WebUiPayloadJson
import dev.yoda.harmon.runtime.LiveSamplingLease
import dev.yoda.harmon.runtime.LiveSamplingSession
import dev.yoda.harmon.util.failureDescription
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.usleep
import kotlin.time.TimeSource

class LiveUiSampler(
    private val collector: SystemCollector,
    private val calculator: UsageCalculator,
    private val sampleSeconds: Long,
    private val state: LiveUiState,
    private val initialPayload: WebUiPayload,
    private val logError: (String) -> Unit,
) {
    private val lifecycleLock = NSLock()
    private val queue = dispatch_queue_create("dev.yoda.harmon.web.sample", null)
    private val lease = LiveSamplingLease(sampleSeconds)
    private val monotonicOrigin = TimeSource.Monotonic.markNow()
    private val samplePeriodNanoseconds =
        sampleSeconds.saturatingMultiply(NANOSECONDS_PER_SECOND).toULong()
    private var running = false

    init {
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
    }

    fun start() {
        lifecycleLock.lock()
        try {
            check(!running) { "live UI sampler is already running" }
            running = true
        } finally {
            lifecycleLock.unlock()
        }
        dispatch_async(queue) { sampleLoop() }
    }

    fun stop() {
        lifecycleLock.lock()
        try {
            running = false
        } finally {
            lifecycleLock.unlock()
        }
    }

    /** Only an authenticated `/api/live?...&watch=1` request calls this. */
    fun renewLease() {
        lifecycleLock.lock()
        try {
            if (running) lease.renew(monotonicNowNanoseconds())
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun sampleLoop() {
        var activeGeneration: ULong? = null
        var session: LiveSamplingSession? = null
        var lastPublished = initialPayload

        while (isRunning()) {
            val generation = currentGeneration()
            if (generation == null) {
                activeGeneration = null
                session = null
                waitIdleSlice()
                continue
            }

            if (generation != activeGeneration) {
                activeGeneration = generation
                session = LiveSamplingSession(
                    collector = collector,
                    calculator = calculator,
                    sampleSeconds = sampleSeconds,
                    monotonicNowNanoseconds = ::monotonicNowNanoseconds,
                    previousPayload = lastPublished,
                )
                val warming = session.warmingPayload()
                if (publishIfCurrent(generation, warming)) lastPublished = warming
            }

            val currentSession = session ?: continue
            val captureStartedAt = monotonicNowNanoseconds()
            val payload = try {
                currentSession.capture()
            } catch (failure: Throwable) {
                logError("live UI sample failed: ${failureDescription(failure)}")
                waitForNextSample(generation, captureStartedAt)
                continue
            }
            if (publishIfCurrent(generation, payload)) lastPublished = payload
            waitForNextSample(generation, captureStartedAt)
        }
    }

    private fun publishIfCurrent(generation: ULong, payload: WebUiPayload): Boolean {
        if (!accepts(generation)) return false
        return try {
            state.update(WebUiPayloadJson.encode(payload))
            true
        } catch (failure: Throwable) {
            logError("live UI sample publish failed: ${failureDescription(failure)}")
            false
        }
    }

    private fun currentGeneration(): ULong? {
        lifecycleLock.lock()
        return try {
            if (running) lease.activeGeneration(monotonicNowNanoseconds()) else null
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun accepts(generation: ULong): Boolean {
        lifecycleLock.lock()
        return try {
            running && lease.accepts(generation, monotonicNowNanoseconds())
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun isRunning(): Boolean {
        lifecycleLock.lock()
        return try {
            running
        } finally {
            lifecycleLock.unlock()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun waitForNextSample(generation: ULong, captureStartedAt: ULong) {
        val deadline = captureStartedAt.saturatingAdd(samplePeriodNanoseconds)
        while (accepts(generation)) {
            val now = monotonicNowNanoseconds()
            if (now >= deadline) return
            val remainingMicroseconds = ((deadline - now) / NANOSECONDS_PER_MICROSECOND)
                .coerceAtLeast(1uL)
            usleep(minOf(ACTIVE_SLICE_MICROSECONDS.toULong(), remainingMicroseconds).toUInt())
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun waitIdleSlice() {
        if (isRunning()) usleep(IDLE_SLICE_MICROSECONDS)
    }

    private fun monotonicNowNanoseconds(): ULong =
        monotonicOrigin.elapsedNow().inWholeNanoseconds.coerceAtLeast(0).toULong()

    private companion object {
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val NANOSECONDS_PER_MICROSECOND = 1_000uL
        const val ACTIVE_SLICE_MICROSECONDS = 100_000u
        const val IDLE_SLICE_MICROSECONDS = 250_000u
    }
}

class LiveUiRuntime(
    private val state: LiveUiState,
    private val sampler: LiveUiSampler,
    private val token: String,
    private val endpointStore: LiveUiEndpointStore = LiveUiEndpointStore(),
    logError: (String) -> Unit,
) {
    private val server = LiveUiServer(
        state = state,
        token = token,
        onWatch = sampler::renewLease,
        logError = logError,
    )
    private var endpoint: LiveUiEndpoint? = null

    fun start(): LiveUiEndpoint {
        check(endpoint == null) { "live UI runtime is already running" }
        val started = LiveUiEndpoint(server.start(), token)
        try {
            endpointStore.publish(started)
            sampler.start()
        } catch (failure: Throwable) {
            server.stop()
            endpointStore.removeIfCurrent(started)
            throw failure
        }
        endpoint = started
        return started
    }

    fun stop() {
        val current = endpoint ?: return
        endpoint = null
        sampler.stop()
        server.stop()
        endpointStore.removeIfCurrent(current)
    }

    companion object {
        fun production(
            collector: SystemCollector,
            terminalApplications: Set<String>,
            sampleSeconds: Long,
            endpointStore: LiveUiEndpointStore = LiveUiEndpointStore(),
            token: String = generateLiveUiToken(),
            logError: (String) -> Unit,
        ): LiveUiRuntime {
            val warming = WebUiPayloadFactory.warming(sampleSeconds.toDouble())
            val state = LiveUiState(WebUiPayloadJson.encode(warming))
            return LiveUiRuntime(
                state = state,
                sampler = LiveUiSampler(
                    collector = collector,
                    calculator = UsageCalculator(terminalApplications),
                    sampleSeconds = sampleSeconds,
                    state = state,
                    initialPayload = warming,
                    logError = logError,
                ),
                token = token,
                endpointStore = endpointStore,
                logError = logError,
            )
        }
    }
}

private fun Long.saturatingMultiply(other: Long): Long =
    if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private fun ULong.saturatingAdd(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other
