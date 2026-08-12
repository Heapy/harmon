import dev.yoda.harmon.web.LiveUiEndpoint
import dev.yoda.harmon.web.generateLiveUiToken
import dev.yoda.harmon.web.liveUiWatchRequested
import dev.yoda.harmon.web.secureEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveUiEndpointTest {
    @Test
    fun endpointRoundTripsAndBuildsAnAuthenticatedLoopbackUrl() {
        val endpoint = LiveUiEndpoint(49_321, "a".repeat(64))

        assertEquals(endpoint, LiveUiEndpoint.parse(endpoint.encode()))
        assertEquals(
            "http://127.0.0.1:49321/?token=${"a".repeat(64)}",
            endpoint.url,
        )
        assertEquals(
            "http://127.0.0.1:49321/api/live?token=${"a".repeat(64)}",
            endpoint.apiUrl,
        )
    }

    @Test
    fun rejectsInvalidManifestFieldsAndTokens() {
        assertFailsWith<IllegalArgumentException> {
            LiveUiEndpoint.parse("port=0\ntoken=${"a".repeat(64)}\n")
        }
        assertFailsWith<IllegalArgumentException> {
            LiveUiEndpoint.parse("port=1234\ntoken=not-secret\n")
        }
        assertFailsWith<IllegalArgumentException> {
            LiveUiEndpoint.parse("port=1234\ntoken=${"a".repeat(64)}\nextra=x\n")
        }
    }

    @Test
    fun tokenComparisonChecksContentAndLength() {
        val token = "b".repeat(64)

        assertTrue(secureEquals(token, token))
        assertFalse(secureEquals(token, "b".repeat(63)))
        assertFalse(secureEquals(token, "b".repeat(63) + "c"))
    }

    @Test
    fun generatedTokenHasTheManifestFormatAndFreshEntropy() {
        val first = generateLiveUiToken()
        val second = generateLiveUiToken()

        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(second.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first == second)
    }

    @Test
    fun onlyExplicitApiWatchRequestsRenewTheSamplerLease() {
        assertTrue(liveUiWatchRequested("/api/live?token=secret&watch=1"))
        assertTrue(liveUiWatchRequested("/api/live?watch=1&token=secret"))

        assertFalse(liveUiWatchRequested("/api/live?token=secret"))
        assertFalse(liveUiWatchRequested("/api/live?token=secret&watch=0"))
        assertFalse(liveUiWatchRequested("/?token=secret&watch=1"))
        assertFalse(liveUiWatchRequested("/health?watch=1"))
    }

}
