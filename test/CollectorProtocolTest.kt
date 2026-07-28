import dev.yoda.harmon.ipc.CollectorProtocol
import dev.yoda.harmon.ipc.CollectorProtocolException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CollectorProtocolTest {
    @Test
    fun roundTripsRawSnapshotAsJson() {
        val original = rawSnapshot(
            monotonicNs = 3_000_000_000u,
            processes = listOf(
                rawProcess(
                    pid = 123,
                    compressedOrPagedOut = 32uL * 1_048_576uL,
                ),
            ),
        )

        val decoded = CollectorProtocol.decode(CollectorProtocol.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun rejectsAnIncompatibleProtocolVersion() {
        val payload = CollectorProtocol.encode(
            rawSnapshot(
                monotonicNs = 1_000_000_000u,
                processes = emptyList(),
            ),
        ).replace("\"protocolVersion\":1", "\"protocolVersion\":2")

        assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(payload)
        }
    }

    @Test
    fun blamesTheProtocolVersionRatherThanTheUnknownFieldOfANewerCollector() {
        val payload = withUnknownField(
            encodedSnapshot().replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
        )

        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(payload)
        }

        assertContains(assertNotNull(failure.message), "Unsupported collector protocol 2")
    }

    @Test
    fun stillRejectsAnUnknownFieldWithinTheSupportedVersion() {
        val payload = withUnknownField(encodedSnapshot())

        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(payload)
        }

        assertContains(assertNotNull(failure.message), "invalid JSON")
    }

    @Test
    fun reportsMalformedJsonAsInvalidJson() {
        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode("{\"protocolVersion\":1,")
        }

        assertContains(assertNotNull(failure.message), "invalid JSON")
    }

    private fun encodedSnapshot(): String = CollectorProtocol.encode(
        rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = emptyList(),
        ),
    )

    private fun withUnknownField(payload: String): String =
        payload.replaceFirst("{", "{\"fieldFromTheFuture\":true,")
}
