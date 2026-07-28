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
    const val VERSION = 1

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

    fun decode(payload: String): RawSystemSnapshot {
        // the snapshot frame reaches tens of megabytes, so it is parsed once and the version is
        // read from the parsed tree instead of decoding the payload a second time
        val element = try {
            json.parseToJsonElement(payload)
        } catch (failure: SerializationException) {
            throw CollectorProtocolException("Collector returned invalid JSON", failure)
        }
        val version = protocolVersionOf(element)
        if (version != null && version != VERSION) {
            throw CollectorProtocolException(
                "Unsupported collector protocol $version; expected $VERSION",
            )
        }
        val envelope = try {
            json.decodeFromJsonElement(CollectorEnvelope.serializer(), element)
        } catch (failure: SerializationException) {
            throw CollectorProtocolException("Collector returned invalid JSON", failure)
        } catch (failure: IllegalArgumentException) {
            throw CollectorProtocolException("Collector returned invalid snapshot data", failure)
        }
        return envelope.snapshot
    }

    // a missing, quoted or non-integer version is left to the strict decoder below, which names the
    // offending field instead of reporting a version nobody sent
    private fun protocolVersionOf(element: JsonElement): Int? {
        val field = (element as? JsonObject)?.get(PROTOCOL_VERSION_FIELD) as? JsonPrimitive ?: return null
        return if (field.isString) null else field.intOrNull
    }

    private const val PROTOCOL_VERSION_FIELD = "protocolVersion"
}

@Serializable
private data class CollectorEnvelope(
    val protocolVersion: Int,
    val snapshot: RawSystemSnapshot,
)
