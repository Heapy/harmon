import dev.yoda.harmon.ipc.CollectorProtocol
import dev.yoda.harmon.ipc.CollectorProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
