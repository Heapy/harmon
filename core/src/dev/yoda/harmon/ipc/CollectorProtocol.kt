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
    /**
     * 2 since the CPU counters of a raw process sample carry real nanoseconds instead of mach
     * absolute time. The collector and the agent are a matched pair: a version bump is how a
     * half-upgraded install fails loudly instead of reporting 41x-low CPU on Apple Silicon.
     */
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

    /**
     * Decodes a frame in a single strict pass, and only re-reads it when that pass failed.
     *
     * The strict decoder names the offending field, which is misleading when the real cause is a
     * peer speaking another version: a newer collector's extra field would be reported as invalid
     * JSON. So a failure is re-read once, purely to see whether a version mismatch explains it.
     */
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

    /** The protocol error a failed decode really stands for, or null when the version is fine. */
    private fun versionMismatch(payload: String): CollectorProtocolException? {
        val element = try {
            json.parseToJsonElement(payload)
        } catch (_: SerializationException) {
            return null
        }
        val version = protocolVersionOf(element) ?: return null
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

    // only a bare integer is read as a version here: a fractional one is not a version to truncate
    // towards, and the strict decoder's own error describes it better than a version nobody sent
    private fun protocolVersionOf(element: JsonElement): Int? {
        val field = (element as? JsonObject)?.get(PROTOCOL_VERSION_FIELD) as? JsonPrimitive
            ?: return null
        return if (field.isString) null else field.intOrNull
    }

    private const val PROTOCOL_VERSION_FIELD = "protocolVersion"
}

/**
 * Stands in for a frame that carries no version field at all. A peer that dropped or renamed it is
 * a protocol mismatch, not malformed JSON, and has to be reported as one — which only works while
 * this is also what [CollectorEnvelope] defaults to.
 */
private const val MISSING_PROTOCOL_VERSION = 0

@Serializable
private data class CollectorEnvelope(
    val protocolVersion: Int = MISSING_PROTOCOL_VERSION,
    val snapshot: RawSystemSnapshot,
)
