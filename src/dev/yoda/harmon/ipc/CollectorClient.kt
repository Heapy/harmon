package dev.yoda.harmon.ipc

import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.CollectionException
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.nativebridge.ipc.HM_MAX_JSON_FRAME_SIZE
import dev.yoda.harmon.nativebridge.ipc.hm_close_descriptor
import dev.yoda.harmon.nativebridge.ipc.hm_free
import dev.yoda.harmon.nativebridge.ipc.hm_receive_json_frame
import dev.yoda.harmon.nativebridge.ipc.hm_unix_connect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.strerror

class CollectorClient(
    private val socketPath: String,
) : SystemCollector {
    init {
        require(socketPath.isNotBlank()) { "socketPath must not be blank" }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun capture(): RawSystemSnapshot =
        CollectorProtocol.decode(receivePayload())

    fun probeProtocolVersion(): Int {
        val payload = receivePayload()
        val version = CollectorProtocol.reportedVersion(payload)
            ?: throw CollectorProtocolException("Collector did not return a protocol version")
        if (version == CollectorProtocol.VERSION) {
            CollectorProtocol.decode(payload)
        }
        return version
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun receivePayload(): String = memScoped {
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
                payload.toKString()
            } finally {
                hm_free(payload)
            }
        } finally {
            hm_close_descriptor(descriptor)
        }
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
