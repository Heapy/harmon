package io.heapy.harmon.ipc

import io.heapy.harmon.model.RawSystemSnapshot
import io.heapy.harmon.monitor.CollectionException
import io.heapy.harmon.monitor.CollectionProfile
import io.heapy.harmon.monitor.SystemCollector
import io.heapy.harmon.nativebridge.ipc.HM_MAX_JSON_FRAME_SIZE
import io.heapy.harmon.nativebridge.ipc.hm_close_descriptor
import io.heapy.harmon.nativebridge.ipc.hm_free
import io.heapy.harmon.nativebridge.ipc.hm_receive_json_frame
import io.heapy.harmon.nativebridge.ipc.hm_send_json_frame
import io.heapy.harmon.nativebridge.ipc.hm_unix_connect
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
    override fun capture(profile: CollectionProfile): RawSystemSnapshot = withConnection { descriptor ->
        CollectorProtocol.decodeHello(receivePayload(descriptor))
        sendPayload(descriptor, CollectorProtocol.encodeCapture(profile))
        CollectorProtocol.decodeSnapshot(receivePayload(descriptor), profile).snapshot
    }

    fun probeProtocolVersion(): Int = withConnection { descriptor ->
        val hello = receivePayload(descriptor)
        val version = CollectorProtocol.reportedVersion(hello)
            ?: throw CollectorProtocolException("Collector did not return a protocol version")
        if (version == CollectorProtocol.VERSION) {
            CollectorProtocol.decodeHello(hello)
            sendPayload(descriptor, CollectorProtocol.encodeProbe())
            CollectorProtocol.decodeAck(receivePayload(descriptor))
        }
        version
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> withConnection(block: (Int) -> T): T {
        val descriptor = hm_unix_connect(socketPath)
        if (descriptor < 0) {
            throw nativeCollectionFailure("Unable to connect to collector at $socketPath")
        }
        try {
            return block(descriptor)
        } finally {
            hm_close_descriptor(descriptor)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun receivePayload(descriptor: Int): String = memScoped {
        val size = alloc<UIntVar>()
        val payload = hm_receive_json_frame(
            descriptor,
            HM_MAX_JSON_FRAME_SIZE,
            size.ptr,
        ) ?: throw nativeCollectionFailure("Unable to receive collector protocol frame")
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
