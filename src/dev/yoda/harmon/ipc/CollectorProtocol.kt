package dev.yoda.harmon.ipc

import dev.yoda.harmon.model.RawSystemSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        val envelope = try {
            json.decodeFromString<CollectorEnvelope>(payload)
        } catch (failure: SerializationException) {
            throw CollectorProtocolException("Collector returned invalid JSON", failure)
        } catch (failure: IllegalArgumentException) {
            throw CollectorProtocolException("Collector returned invalid snapshot data", failure)
        }
        if (envelope.protocolVersion != VERSION) {
            throw CollectorProtocolException(
                "Unsupported collector protocol ${envelope.protocolVersion}; expected $VERSION",
            )
        }
        return envelope.snapshot
    }
}

@Serializable
private data class CollectorEnvelope(
    val protocolVersion: Int,
    val snapshot: RawSystemSnapshot,
)
