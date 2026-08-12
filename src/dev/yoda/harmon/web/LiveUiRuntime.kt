package dev.yoda.harmon.web

import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.report.WebUiPayloadJson
import dev.yoda.harmon.runtime.LiveSamplingSession
import dev.yoda.harmon.util.failureDescription
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.usleep

class LiveUiSampler(
    private val collector: SystemCollector,
    private val calculator: UsageCalculator,
    private val sampleSeconds: Long,
    private val state: LiveUiState,
    private val logError: (String) -> Unit,
) {
    private val lifecycleLock = NSLock()
    private val queue = dispatch_queue_create("dev.yoda.harmon.web.sample", null)
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

    private fun sampleLoop() {
        val session = LiveSamplingSession(collector, calculator, sampleSeconds)

        while (isRunning()) {
            try {
                state.update(WebUiPayloadJson.encode(session.capture()))
            } catch (failure: Throwable) {
                logError("live UI sample publish failed: ${failureDescription(failure)}")
            }
            waitForNextSample()
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
    private fun waitForNextSample() {
        var remaining = sampleSeconds * SLICES_PER_SECOND
        while (remaining > 0 && isRunning()) {
            usleep(SLICE_MICROSECONDS)
            remaining -= 1
        }
    }

    private companion object {
        const val SLICES_PER_SECOND = 10L
        const val SLICE_MICROSECONDS = 100_000u
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
                    logError = logError,
                ),
                token = token,
                endpointStore = endpointStore,
                logError = logError,
            )
        }
    }
}
