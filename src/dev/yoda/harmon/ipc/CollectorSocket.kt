package dev.yoda.harmon.ipc

import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.CollectionException
import dev.yoda.harmon.monitor.DarwinSystemCollector
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.nativebridge.HM_MAX_JSON_FRAME_SIZE
import dev.yoda.harmon.nativebridge.hm_close_descriptor
import dev.yoda.harmon.nativebridge.hm_free
import dev.yoda.harmon.nativebridge.hm_receive_json_frame
import dev.yoda.harmon.nativebridge.hm_remove_socket
import dev.yoda.harmon.nativebridge.hm_send_json_frame
import dev.yoda.harmon.nativebridge.hm_sleep_millis
import dev.yoda.harmon.nativebridge.hm_unix_accept
import dev.yoda.harmon.nativebridge.hm_unix_connect
import dev.yoda.harmon.nativebridge.hm_unix_server_open
import dev.yoda.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.errno
import platform.posix.strerror
import kotlin.time.Clock

/** `hm_unix_accept` result for a peer whose UID is not allowed to talk to the collector. */
const val UNAUTHORIZED_CLIENT = -2

/** Consecutive failed `accept` calls the collector tolerates before it stops serving. */
const val CONSECUTIVE_ACCEPT_FAILURE_LIMIT = 16

/** Pause after a failed `accept`, so a permanently broken listener cannot spin the CPU. */
const val ACCEPT_FAILURE_PAUSE_MILLISECONDS = 100uL

/** What the collector loop should do with the outcome of a single `accept` call. */
enum class AcceptDecision {
    /** The descriptor is usable; serve the client. */
    SERVE,

    /** The peer failed the UID check; it was already closed, keep listening. */
    REJECT,

    /** `accept` failed; pause and keep listening. */
    RETRY,

    /** `accept` keeps failing; the listener is broken and the daemon must stop. */
    FATAL,
}

data class AcceptOutcome(
    val decision: AcceptDecision,
    val consecutiveFailures: Int,
)

/**
 * Decides what an `hm_unix_accept` [result] means for a collector that has already seen
 * [consecutiveFailures] failed accepts in a row, and how many failures stand after it.
 *
 * A rejected peer is a normal event and leaves the failure count untouched, so an unprivileged
 * user cannot bring the daemon down by connecting in a loop. Only genuine `accept` errors count,
 * and only [CONSECUTIVE_ACCEPT_FAILURE_LIMIT] of them in a row — a transient one must not kill a
 * root daemon.
 *
 * Pure on purpose: no socket, no logging, no sleeping. The caller does those, which is what keeps
 * the policy testable without a listening socket.
 */
fun classifyAccept(result: Int, consecutiveFailures: Int): AcceptOutcome = when {
    result >= 0 -> AcceptOutcome(AcceptDecision.SERVE, consecutiveFailures = 0)
    result == UNAUTHORIZED_CLIENT -> AcceptOutcome(AcceptDecision.REJECT, consecutiveFailures)
    consecutiveFailures + 1 >= CONSECUTIVE_ACCEPT_FAILURE_LIMIT ->
        AcceptOutcome(AcceptDecision.FATAL, consecutiveFailures + 1)
    else -> AcceptOutcome(AcceptDecision.RETRY, consecutiveFailures + 1)
}

class CollectorClient(
    private val socketPath: String,
) : SystemCollector {
    init {
        require(socketPath.isNotBlank()) { "socketPath must not be blank" }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun capture(): RawSystemSnapshot = memScoped {
        val descriptor = hm_unix_connect(socketPath)
        if (descriptor < 0) {
            throw nativeCollectionFailure("Unable to connect to collector at $socketPath")
        }
        try {
            val size = alloc<UIntVar>()
            val payload = hm_receive_json_frame(
                descriptor,
                HM_MAX_JSON_FRAME_SIZE,
                size.ptr,
            ) ?: throw nativeCollectionFailure("Unable to receive collector snapshot")
            try {
                CollectorProtocol.decode(payload.toKString())
            } finally {
                hm_free(payload)
            }
        } finally {
            hm_close_descriptor(descriptor)
        }
    }
}

class CollectorServer(
    private val socketPath: String,
    private val allowedUserId: UInt,
    private val socketGroupId: UInt,
    private val collector: SystemCollector = DarwinSystemCollector(),
    private val log: (String) -> Unit = ::println,
    private val logError: (String) -> Unit = ::printError,
) {
    init {
        require(socketPath.isNotBlank()) { "socketPath must not be blank" }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun runForever(): Nothing {
        val serverDescriptor = memScoped {
            hm_unix_server_open(socketPath, socketGroupId)
        }
        if (serverDescriptor < 0) {
            throw nativeCollectionFailure("Unable to open collector socket at $socketPath")
        }

        log(
            "${Clock.System.now()} collector listening at $socketPath; " +
                "allowed UID=$allowedUserId",
        )
        try {
            var consecutiveFailures = 0
            while (true) {
                val attempt = acceptClient(serverDescriptor)
                val outcome = classifyAccept(attempt.result, consecutiveFailures)
                consecutiveFailures = outcome.consecutiveFailures
                when (outcome.decision) {
                    AcceptDecision.SERVE -> serveClient(attempt.result)
                    AcceptDecision.REJECT -> logError(
                        "${Clock.System.now()} rejected collector client UID=" +
                            attempt.peerUserId,
                    )
                    AcceptDecision.RETRY -> {
                        logError(
                            "${Clock.System.now()} collector accept failed " +
                                "($consecutiveFailures/$CONSECUTIVE_ACCEPT_FAILURE_LIMIT), " +
                                "still listening: " + nativeErrorDescription(),
                        )
                        hm_sleep_millis(ACCEPT_FAILURE_PAUSE_MILLISECONDS)
                    }
                    AcceptDecision.FATAL -> throw nativeCollectionFailure(
                        "Collector accept failed $consecutiveFailures times in a row",
                    )
                }
            }
        } finally {
            hm_close_descriptor(serverDescriptor)
            hm_remove_socket(socketPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun serveClient(clientDescriptor: Int) {
        try {
            val snapshot = collector.capture()
            val payload = CollectorProtocol.encode(snapshot)
            if (hm_send_json_frame(clientDescriptor, payload) != 0) {
                logError(
                    "${Clock.System.now()} unable to send collector snapshot: " +
                        nativeErrorDescription(),
                )
            }
        } catch (failure: Throwable) {
            logError(
                "${Clock.System.now()} collector request failed: " +
                    (failure.message ?: failure::class.simpleName),
            )
        } finally {
            hm_close_descriptor(clientDescriptor)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun acceptClient(serverDescriptor: Int): AcceptAttempt = memScoped {
        val peerUserId = alloc<UIntVar>()
        val result = hm_unix_accept(
            serverDescriptor,
            allowedUserId,
            peerUserId.ptr,
        )
        AcceptAttempt(result = result, peerUserId = peerUserId.value)
    }
}

private data class AcceptAttempt(
    val result: Int,
    val peerUserId: UInt,
)

@OptIn(ExperimentalForeignApi::class)
private fun nativeCollectionFailure(action: String): CollectionException =
    CollectionException("$action: ${nativeErrorDescription()}")

@OptIn(ExperimentalForeignApi::class)
private fun nativeErrorDescription(): String {
    val errorCode = errno
    return strerror(errorCode)?.toKString()?.let { "$it (errno $errorCode)" }
        ?: "errno $errorCode"
}
