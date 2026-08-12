package dev.yoda.harmon.ipc

import dev.yoda.harmon.monitor.CollectionException
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.nativebridge.ipc.HM_MAX_JSON_FRAME_SIZE
import dev.yoda.harmon.nativebridge.ipc.hm_close_descriptor
import dev.yoda.harmon.nativebridge.ipc.hm_free
import dev.yoda.harmon.nativebridge.ipc.hm_receive_json_frame
import dev.yoda.harmon.nativebridge.ipc.hm_remove_socket
import dev.yoda.harmon.nativebridge.ipc.hm_send_json_frame
import dev.yoda.harmon.nativebridge.ipc.hm_sleep_millis
import dev.yoda.harmon.nativebridge.ipc.hm_unix_accept
import dev.yoda.harmon.nativebridge.ipc.hm_unix_server_open
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

/** Prevents a permanently broken listener from spinning the CPU. */
private const val ACCEPT_FAILURE_PAUSE_MILLISECONDS = 100uL

class CollectorServer(
    private val socketPath: String,
    private val allowedUserId: UInt,
    private val socketGroupId: UInt,
    private val collector: SystemCollector,
    private val log: (String) -> Unit = ::println,
    private val logError: (String) -> Unit = ::printError,
) {
    private val rejectionLog = RejectionLog()
    private val requestHandler = CollectorRequestHandler(collector)

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
                    attempt.resultOrDescriptor,
                    consecutiveFailures,
                )
                when (acceptDecision(attempt.resultOrDescriptor, consecutiveFailures)) {
                    AcceptDecision.SERVE -> serveClient(attempt.resultOrDescriptor)
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
            sendPayload(clientDescriptor, CollectorProtocol.encodeHello())
            val request = receivePayload(clientDescriptor)
            sendPayload(clientDescriptor, requestHandler.respond(request))
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
    private fun receivePayload(descriptor: Int): String = memScoped {
        val size = alloc<UIntVar>()
        val payload = hm_receive_json_frame(
            descriptor,
            HM_MAX_JSON_FRAME_SIZE,
            size.ptr,
        ) ?: throw nativeCollectionFailure("Unable to receive collector request")
        try {
            payload.toKString()
        } finally {
            hm_free(payload)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun sendPayload(descriptor: Int, payload: String) {
        if (hm_send_json_frame(descriptor, payload) != 0) {
            throw nativeCollectionFailure("Unable to send collector protocol frame")
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
        AcceptAttempt(resultOrDescriptor = result, peerUserId = peerUserId.value)
    }
}

/** Public because Kotlin/Native test compilations cannot see production internal declarations. */
class CollectorRequestHandler(
    private val collector: SystemCollector,
) {
    fun respond(payload: String): String = when (val request = CollectorProtocol.decodeRequest(payload)) {
        CollectorRequest.Probe -> CollectorProtocol.encodeAck()
        is CollectorRequest.Capture -> CollectorProtocol.encodeSnapshot(
            snapshot = collector.capture(request.profile),
            appliedProfile = request.profile,
        )
    }
}

/** [resultOrDescriptor] is a descriptor only when AcceptDecision is SERVE. */
private data class AcceptAttempt(
    val resultOrDescriptor: Int,
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
