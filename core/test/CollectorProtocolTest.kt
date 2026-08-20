import io.heapy.harmon.ipc.CollectorProtocol
import io.heapy.harmon.ipc.CollectorProtocolException
import io.heapy.harmon.ipc.CollectorRequest
import io.heapy.harmon.monitor.CollectionProfile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val CURRENT_VERSION_FIELD = "\"protocolVersion\":${CollectorProtocol.VERSION}"

class CollectorProtocolTest {
    @Test
    fun roundTripsBothCaptureProfilesAndTheirAppliedProfile() {
        val original = rawSnapshot(
            monotonicNs = 3_000_000_000u,
            processes = listOf(
                rawProcess(pid = 123, compressedOrPagedOut = 32uL * 1_048_576uL),
            ),
        )

        for (profile in CollectionProfile.entries) {
            val request = assertIs<CollectorRequest.Capture>(
                CollectorProtocol.decodeRequest(CollectorProtocol.encodeCapture(profile)),
            )
            val decoded = CollectorProtocol.decodeSnapshot(
                CollectorProtocol.encodeSnapshot(original, profile),
                expectedProfile = profile,
            )

            assertEquals(profile, request.profile)
            assertEquals(profile, decoded.appliedProfile)
            assertEquals(original, decoded.snapshot)
        }
    }

    @Test
    fun helloProbeAndAckUseStrictVersionThreeFrames() {
        CollectorProtocol.decodeHello(CollectorProtocol.encodeHello())
        assertEquals(
            CollectorRequest.Probe,
            CollectorProtocol.decodeRequest(CollectorProtocol.encodeProbe()),
        )
        CollectorProtocol.decodeAck(CollectorProtocol.encodeAck())
        assertEquals(3, CollectorProtocol.VERSION)
    }

    @Test
    fun rejectsAProfileMismatchInsteadOfSilentlyApplyingIt() {
        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeSnapshot(
                CollectorProtocol.encodeSnapshot(emptySnapshot(), CollectionProfile.FULL),
                expectedProfile = CollectionProfile.LIVE_FAST,
            )
        }

        assertContains(assertNotNull(failure.message), "applied profile FULL")
        assertContains(assertNotNull(failure.message), "expected LIVE_FAST")
    }

    @Test
    fun rejectsAnUnknownCaptureProfile() {
        val payload = CollectorProtocol.encodeCapture(CollectionProfile.FULL)
            .replace("\"FULL\"", "\"FUTURE\"")

        val failure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeRequest(payload)
        }

        assertContains(assertNotNull(failure.message), "invalid collector request")
    }

    @Test
    fun rejectsUnknownFieldsInEveryFrameDirection() {
        val frames = listOf<Triple<String, String, () -> Unit>>(
            Triple("hello", "Collector returned") {
                CollectorProtocol.decodeHello(withUnknownField(CollectorProtocol.encodeHello()))
            },
            Triple("request", "Collector received") {
                CollectorProtocol.decodeRequest(
                    withUnknownField(CollectorProtocol.encodeCapture(CollectionProfile.FULL)),
                )
            },
            Triple("ack", "Collector returned") {
                CollectorProtocol.decodeAck(withUnknownField(CollectorProtocol.encodeAck()))
            },
            Triple("snapshot", "Collector returned") {
                CollectorProtocol.decodeSnapshot(
                    withUnknownField(
                        CollectorProtocol.encodeSnapshot(emptySnapshot(), CollectionProfile.FULL),
                    ),
                    CollectionProfile.FULL,
                )
            },
        )

        for ((name, direction, decode) in frames) {
            val failure = assertFailsWith<CollectorProtocolException>(name, decode)
            assertContains(assertNotNull(failure.message), "invalid")
            assertContains(assertNotNull(failure.message), direction)
        }
    }

    @Test
    fun reportsV2V3MismatchesBeforeDecodingUnknownPayload() {
        val newer = withUnknownField(CollectorProtocol.encodeHello())
            .withVersion("\"protocolVersion\":4")
        val older = """{"protocolVersion":2,"snapshot":{}}"""

        val newerFailure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeHello(newer)
        }
        val olderFailure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeHello(older)
        }

        assertContains(assertNotNull(newerFailure.message), "Unsupported collector protocol 4")
        assertContains(assertNotNull(olderFailure.message), "Unsupported collector protocol 2")
        assertEquals(2, CollectorProtocol.reportedVersion(older))
    }

    @Test
    fun reportsMissingMalformedAndNonIntegralVersionsPrecisely() {
        val missing = CollectorProtocol.encodeHello().replaceFirst("$CURRENT_VERSION_FIELD,", "")
        val missingFailure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeHello(missing)
        }
        val malformedFailure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeHello("{$CURRENT_VERSION_FIELD,")
        }
        val fractionalFailure = assertFailsWith<CollectorProtocolException> {
            CollectorProtocol.decodeHello(
                CollectorProtocol.encodeHello().withVersion("\"protocolVersion\":3.5"),
            )
        }

        assertContains(assertNotNull(missingFailure.message), "did not report")
        assertContains(assertNotNull(malformedFailure.message), "did not report")
        assertContains(assertNotNull(fractionalFailure.message), "did not report")
    }

    private fun emptySnapshot() = rawSnapshot(
        monotonicNs = 1_000_000_000u,
        processes = emptyList(),
    )

    private fun String.withVersion(field: String): String =
        replaceFirst(CURRENT_VERSION_FIELD, field)

    private fun withUnknownField(payload: String): String =
        payload.replaceFirst("{", "{\"fieldFromTheFuture\":true,")
}
