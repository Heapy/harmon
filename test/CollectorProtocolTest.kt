import dev.yoda.harmon.ipc.CollectorProtocol
import dev.yoda.harmon.ipc.CollectorProtocolException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private val CURRENT_VERSION_FIELD = "\"protocolVersion\":${CollectorProtocol.VERSION}"

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
        val payload = encodedSnapshot().withVersion("\"protocolVersion\":3")

        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(payload)
        }

        assertContains(assertNotNull(failure.message), "Unsupported collector protocol 3")
    }

    @Test
    fun blamesTheProtocolVersionRatherThanTheUnknownFieldOfANewerCollector() {
        val payload = withUnknownField(encodedSnapshot().withVersion("\"protocolVersion\":3"))

        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(payload)
        }

        assertContains(assertNotNull(failure.message), "Unsupported collector protocol 3")
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
            CollectorProtocol.decode("{$CURRENT_VERSION_FIELD,")
        }

        assertContains(assertNotNull(failure.message), "invalid JSON")
    }

    /**
     * A peer that dropped or renamed the field speaks a protocol Harmon cannot read, which is a
     * version problem and has to be reported as one — calling it malformed JSON sends the reader
     * looking for a syntax error that is not there.
     */
    @Test
    fun reportsAMissingProtocolVersionAsAProtocolProblem() {
        val payload = encodedSnapshot().replaceFirst("$CURRENT_VERSION_FIELD,", "")

        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(payload)
        }

        assertContains(assertNotNull(failure.message), "did not report a protocol version")
    }

    /**
     * The version pre-read deliberately ignores anything that is not a bare integer and lets the
     * strict decoder judge it: a quoted number is still the number it spells, and a fractional
     * one is malformed rather than a version to truncate towards.
     */
    @Test
    fun leavesAVersionThatIsNotABareIntegerToTheStrictDecoder() {
        val quoted = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(encodedSnapshot().withVersion("\"protocolVersion\":\"3\""))
        }
        val fractional = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode(encodedSnapshot().withVersion("\"protocolVersion\":2.5"))
        }

        assertContains(assertNotNull(quoted.message), "Unsupported collector protocol 3")
        assertContains(assertNotNull(fractional.message), "invalid JSON")
    }

    @Test
    fun reportsANonObjectFrameAsInvalidJson() {
        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decode("[$CURRENT_VERSION_FIELD]")
        }

        assertContains(assertNotNull(failure.message), "invalid JSON")
    }

    private fun encodedSnapshot(): String = CollectorProtocol.encode(
        rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = emptyList(),
        ),
    )

    private fun String.withVersion(field: String): String =
        replaceFirst(CURRENT_VERSION_FIELD, field)

    private fun withUnknownField(payload: String): String =
        payload.replaceFirst("{", "{\"fieldFromTheFuture\":true,")
}
