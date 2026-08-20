package io.heapy.harmon.ipc

import io.heapy.harmon.model.RawSystemSnapshot
import io.heapy.harmon.monitor.CollectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class CollectorProtocolException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

sealed interface CollectorRequest {
    data object Probe : CollectorRequest

    data class Capture(val profile: CollectionProfile) : CollectorRequest
}

data class CollectorSnapshot(
    val snapshot: RawSystemSnapshot,
    val appliedProfile: CollectionProfile,
)

object CollectorProtocol {
    const val VERSION = 3

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encodeHello(): String = json.encodeToString(
        HelloFrame(protocolVersion = VERSION, kind = HELLO_KIND),
    )

    fun decodeHello(payload: String) {
        val frame = decodeFrame<HelloFrame>(payload, HELLO_KIND, "hello")
        requireKind(frame.kind, HELLO_KIND, "hello")
    }

    fun encodeProbe(): String = json.encodeToString(
        ProbeFrame(protocolVersion = VERSION, kind = PROBE_KIND),
    )

    fun encodeCapture(profile: CollectionProfile): String = json.encodeToString(
        CaptureFrame(
            protocolVersion = VERSION,
            kind = CAPTURE_KIND,
            profile = profile,
        ),
    )

    fun decodeRequest(payload: String): CollectorRequest {
        requireSupportedVersion(payload)
        return when (kindOf(payload)) {
            PROBE_KIND -> {
                val frame = decodeStrict<ProbeFrame>(
                    payload,
                    "collector request",
                    invalidSubject = "Collector received",
                )
                requireKind(
                    frame.kind,
                    PROBE_KIND,
                    "collector request",
                    invalidSubject = "Collector received",
                )
                CollectorRequest.Probe
            }
            CAPTURE_KIND -> {
                val frame = decodeStrict<CaptureFrame>(
                    payload,
                    "collector request",
                    invalidSubject = "Collector received",
                )
                requireKind(
                    frame.kind,
                    CAPTURE_KIND,
                    "collector request",
                    invalidSubject = "Collector received",
                )
                CollectorRequest.Capture(frame.profile)
            }
            else -> throw CollectorProtocolException("Collector received an unknown request frame")
        }
    }

    fun encodeAck(): String = json.encodeToString(
        AckFrame(protocolVersion = VERSION, kind = ACK_KIND),
    )

    fun decodeAck(payload: String) {
        val frame = decodeFrame<AckFrame>(payload, ACK_KIND, "acknowledgement")
        requireKind(frame.kind, ACK_KIND, "acknowledgement")
    }

    fun encodeSnapshot(
        snapshot: RawSystemSnapshot,
        appliedProfile: CollectionProfile,
    ): String = json.encodeToString(
        SnapshotFrame(
            protocolVersion = VERSION,
            kind = SNAPSHOT_KIND,
            appliedProfile = appliedProfile,
            snapshot = snapshot,
        ),
    )

    fun decodeSnapshot(
        payload: String,
        expectedProfile: CollectionProfile,
    ): CollectorSnapshot {
        val frame = decodeFrame<SnapshotFrame>(payload, SNAPSHOT_KIND, "snapshot")
        requireKind(frame.kind, SNAPSHOT_KIND, "snapshot")
        if (frame.appliedProfile != expectedProfile) {
            throw CollectorProtocolException(
                "Collector applied profile ${frame.appliedProfile}, expected $expectedProfile",
            )
        }
        return CollectorSnapshot(frame.snapshot, frame.appliedProfile)
    }

    fun reportedVersion(payload: String): Int? {
        val element = try {
            json.parseToJsonElement(payload)
        } catch (_: SerializationException) {
            return null
        }
        return protocolVersionOf(element)
    }

    private inline fun <reified T> decodeFrame(
        payload: String,
        expectedKind: String,
        description: String,
    ): T {
        requireSupportedVersion(payload)
        if (kindOf(payload) != expectedKind) {
            throw CollectorProtocolException("Collector returned an invalid $description frame")
        }
        return decodeStrict(payload, description)
    }

    private inline fun <reified T> decodeStrict(
        payload: String,
        description: String,
        invalidSubject: String = "Collector returned",
    ): T = try {
        json.decodeFromString<T>(payload)
    } catch (failure: SerializationException) {
        throw versionMismatch(payload)
            ?: CollectorProtocolException("$invalidSubject invalid $description JSON", failure)
    } catch (failure: IllegalArgumentException) {
        throw versionMismatch(payload)
            ?: CollectorProtocolException("$invalidSubject invalid $description data", failure)
    }

    private fun requireSupportedVersion(payload: String) {
        val version = reportedVersion(payload) ?: throw CollectorProtocolException(
            "Collector did not report a protocol version; expected $VERSION",
        )
        if (version != VERSION) {
            throw CollectorProtocolException(unsupportedVersion(version))
        }
    }

    private fun requireKind(
        actual: String,
        expected: String,
        description: String,
        invalidSubject: String = "Collector returned",
    ) {
        if (actual != expected) {
            throw CollectorProtocolException("$invalidSubject an invalid $description frame")
        }
    }

    private fun versionMismatch(payload: String): CollectorProtocolException? {
        val version = reportedVersion(payload) ?: return null
        return if (version == VERSION) null else CollectorProtocolException(unsupportedVersion(version))
    }

    private fun unsupportedVersion(version: Int): String =
        if (version == MISSING_PROTOCOL_VERSION) {
            "Collector did not report a protocol version; expected $VERSION"
        } else {
            "Unsupported collector protocol $version; expected $VERSION"
        }

    private fun kindOf(payload: String): String? {
        val element = try {
            json.parseToJsonElement(payload)
        } catch (_: SerializationException) {
            return null
        }
        return ((element as? JsonObject)?.get(KIND_FIELD) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    }

    // A fractional JSON number is invalid, not a version to truncate.
    private fun protocolVersionOf(element: JsonElement): Int? {
        val field = (element as? JsonObject)?.get(PROTOCOL_VERSION_FIELD) as? JsonPrimitive
            ?: return null
        return if (field.isString) null else field.intOrNull
    }

    private const val PROTOCOL_VERSION_FIELD = "protocolVersion"
    private const val KIND_FIELD = "kind"
    private const val HELLO_KIND = "hello"
    private const val PROBE_KIND = "probe"
    private const val CAPTURE_KIND = "capture"
    private const val ACK_KIND = "ack"
    private const val SNAPSHOT_KIND = "snapshot"
}

/** Must match frame defaults so a missing field is diagnosed as a version mismatch. */
private const val MISSING_PROTOCOL_VERSION = 0
private const val MISSING_KIND = ""

@Serializable
private data class HelloFrame(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val kind: String = MISSING_KIND,
)

@Serializable
private data class ProbeFrame(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val kind: String = MISSING_KIND,
)

@Serializable
private data class CaptureFrame(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val kind: String = MISSING_KIND,
    val profile: CollectionProfile,
)

@Serializable
private data class AckFrame(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val kind: String = MISSING_KIND,
)

@Serializable
private data class SnapshotFrame(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val kind: String = MISSING_KIND,
    val appliedProfile: CollectionProfile,
    val snapshot: RawSystemSnapshot,
)
