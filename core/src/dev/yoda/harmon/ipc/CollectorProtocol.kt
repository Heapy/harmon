package dev.yoda.harmon.ipc

import dev.yoda.harmon.model.RawSystemSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class CollectorProtocolException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

object CollectorProtocol {
    /** Version 2 changed process CPU counters from Mach ticks to nanoseconds. */
    const val VERSION = 2

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(snapshot: RawSystemSnapshot): String =
        json.encodeToString(
            CollectorEnvelope(
                protocolVersion = VERSION,
                snapshot = snapshot,
            ),
        )

    /** Re-parses only after strict decoding fails so a newer envelope reports a version mismatch. */
    fun decode(payload: String): RawSystemSnapshot {
        val envelope = try {
            json.decodeFromString(CollectorEnvelope.serializer(), payload)
        } catch (failure: SerializationException) {
            throw versionMismatch(payload)
                ?: CollectorProtocolException("Collector returned invalid JSON", failure)
        } catch (failure: IllegalArgumentException) {
            throw versionMismatch(payload)
                ?: CollectorProtocolException("Collector returned invalid snapshot data", failure)
        }
        if (envelope.protocolVersion != VERSION) {
            throw CollectorProtocolException(unsupportedVersion(envelope.protocolVersion))
        }
        return envelope.snapshot
    }

    fun reportedVersion(payload: String): Int? {
        val element = try {
            json.parseToJsonElement(payload)
        } catch (_: SerializationException) {
            return null
        }
        return protocolVersionOf(element)
    }

    private fun versionMismatch(payload: String): CollectorProtocolException? {
        val version = reportedVersion(payload) ?: return null
        return if (version == VERSION) {
            null
        } else {
            CollectorProtocolException(unsupportedVersion(version))
        }
    }

    private fun unsupportedVersion(version: Int): String =
        if (version == MISSING_PROTOCOL_VERSION) {
            "Collector did not report a protocol version; expected $VERSION"
        } else {
            "Unsupported collector protocol $version; expected $VERSION"
        }

    // A fractional JSON number is invalid, not a version to truncate.
    private fun protocolVersionOf(element: JsonElement): Int? {
        val field = (element as? JsonObject)?.get(PROTOCOL_VERSION_FIELD) as? JsonPrimitive
            ?: return null
        return if (field.isString) null else field.intOrNull
    }

    private const val PROTOCOL_VERSION_FIELD = "protocolVersion"
}

/** Must match CollectorEnvelope's default so a missing field is diagnosed as a version mismatch. */
private const val MISSING_PROTOCOL_VERSION = 0

@Serializable
private data class CollectorEnvelope(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val snapshot: RawSystemSnapshot,
)
