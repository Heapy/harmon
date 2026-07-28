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
import dev.yoda.harmon.util.failureDescription
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** `hm_unix_accept` result for a peer whose UID is not allowed to talk to the collector. */
const val UNAUTHORIZED_CLIENT = -2

/** Consecutive failed `accept` calls the collector tolerates before it stops serving. */
const val CONSECUTIVE_ACCEPT_FAILURE_LIMIT = 16

/** Pause after a failed `accept`, so a permanently broken listener cannot spin the CPU. */
private const val ACCEPT_FAILURE_PAUSE_MILLISECONDS = 100uL

/** Shortest gap between two logged rejections; the ones in between are counted, not written. */
val REJECTION_LOG_INTERVAL: Duration = 60.seconds

/**
 * Coalesces the rejection log of [CollectorServer] into at most one line per [interval].
 *
 * The collector socket is group-owned, and the installer configures the login user's primary
 * group, which on a stock macOS install is `staff` — so every local account can reach the socket
 * and be rejected on its UID. A line per rejection let any of them drive an unbounded stream into
 * the root-owned, unrotated collector log and fill the boot volume. Each line now stands for
 * however many rejections it coalesced, so the event stays visible while its cost stays bounded.
 *
 * A clock that jumped backwards ends the window rather than extending it: the point is a bound on
 * how often a line is written, and a wall-clock adjustment must not turn that into silence.
 *
 * The clock is a parameter rather than a field so the coalescing can be tested without waiting.
 */
class RejectionLog(private val interval: Duration = REJECTION_LOG_INTERVAL) {
    private var loggedAt: Instant? = null
    private var coalesced = 0

    /** The line to log for a peer rejected at [now], or null while the current window holds. */
    fun record(peerUserId: UInt, now: Instant): String? {
        val elapsed = loggedAt?.let { now - it }
        if (elapsed != null && elapsed >= Duration.ZERO && elapsed < interval) {
            coalesced += 1
            return null
        }
        val suppressed = coalesced
        loggedAt = now
        coalesced = 0
        return "$now rejected collector client UID=$peerUserId" +
            if (suppressed > 0) " (and $suppressed more since the last line)" else ""
    }
}

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

/**
 * Decides what an `hm_unix_accept` [result] means for a collector that has now seen
 * [consecutiveFailures] failed accepts in a row, the current one included.
 *
 * A rejected peer is a normal event and does not count as a failure, so an unprivileged user
 * cannot bring the daemon down by connecting in a loop. Only genuine `accept` errors count, and
 * only [CONSECUTIVE_ACCEPT_FAILURE_LIMIT] of them in a row — a transient one must not kill a root
 * daemon.
 *
 * Pure on purpose: no socket, no logging, no sleeping. The caller does those, and keeps the
 * counter itself, which is what keeps the policy testable without a listening socket.
 */
fun acceptDecision(result: Int, consecutiveFailures: Int): AcceptDecision = when {
    result >= 0 -> AcceptDecision.SERVE
    result == UNAUTHORIZED_CLIENT -> AcceptDecision.REJECT
    consecutiveFailures >= CONSECUTIVE_ACCEPT_FAILURE_LIMIT -> AcceptDecision.FATAL
    else -> AcceptDecision.RETRY
}

/**
 * The consecutive-failure count a collector that stood at [consecutiveFailures] carries after an
 * `hm_unix_accept` returning [result]: a served client clears it, a rejected peer leaves it, and
 * only a genuine error raises it.
 */
fun consecutiveFailuresAfter(result: Int, consecutiveFailures: Int): Int = when {
    result >= 0 -> 0
    result == UNAUTHORIZED_CLIENT -> consecutiveFailures
    else -> consecutiveFailures + 1
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
    private val rejectionLog = RejectionLog()

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
                consecutiveFailures = consecutiveFailuresAfter(
                    attempt.result,
                    consecutiveFailures,
                )
                when (acceptDecision(attempt.result, consecutiveFailures)) {
                    AcceptDecision.SERVE -> serveClient(attempt.result)
                    AcceptDecision.REJECT ->
                        rejectionLog.record(attempt.peerUserId, Clock.System.now())?.let(logError)
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
                    failureDescription(failure),
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
