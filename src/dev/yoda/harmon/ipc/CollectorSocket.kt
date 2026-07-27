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
            while (true) {
                val clientDescriptor = acceptClient(serverDescriptor)
                if (clientDescriptor < 0) {
                    continue
                }
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
        } finally {
            hm_close_descriptor(serverDescriptor)
            hm_remove_socket(socketPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun acceptClient(serverDescriptor: Int): Int = memScoped {
        val peerUserId = alloc<UIntVar>()
        val descriptor = hm_unix_accept(
            serverDescriptor,
            allowedUserId,
            peerUserId.ptr,
        )
        when {
            descriptor >= 0 -> descriptor
            descriptor == UNAUTHORIZED_CLIENT -> {
                logError(
                    "${Clock.System.now()} rejected collector client UID=" +
                        peerUserId.value,
                )
                descriptor
            }
            else -> throw nativeCollectionFailure("Collector accept failed")
        }
    }

    private companion object {
        const val UNAUTHORIZED_CLIENT = -2
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nativeCollectionFailure(action: String): CollectionException =
    CollectionException("$action: ${nativeErrorDescription()}")

@OptIn(ExperimentalForeignApi::class)
private fun nativeErrorDescription(): String {
    val errorCode = errno
    return strerror(errorCode)?.toKString()?.let { "$it (errno $errorCode)" }
        ?: "errno $errorCode"
}
