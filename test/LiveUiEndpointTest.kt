import io.heapy.harmon.web.LiveUiEndpoint
import io.heapy.harmon.web.LiveUiEndpointState
import io.heapy.harmon.web.LiveUiEndpointStore
import io.heapy.harmon.web.LiveUiLauncher
import io.heapy.harmon.web.LiveUiProbeOutcome
import io.heapy.harmon.web.LiveHttpSocketKind
import io.heapy.harmon.web.classifyLiveUiProbe
import io.heapy.harmon.web.configureLiveHttpSocket
import io.heapy.harmon.web.generateLiveUiToken
import io.heapy.harmon.web.liveUiWatchRequested
import io.heapy.harmon.web.secureEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_UNIX
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_GETFL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.close
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.socketpair
import platform.posix.socklen_tVar
import platform.posix.timeval
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveUiEndpointTest {
    @Test
    fun endpointRoundTripsAndBuildsAnAuthenticatedLoopbackUrl() {
        val endpoint = LiveUiEndpoint(49_321, "a".repeat(64))

        assertEquals(endpoint, LiveUiEndpoint.parse(endpoint.encode()))
        assertEquals(
            "http://127.0.0.1:49321/#token=${"a".repeat(64)}",
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
        assertTrue(liveUiWatchRequested("/api/live?watch=1"))

        assertFalse(liveUiWatchRequested("/api/live"))
        assertFalse(liveUiWatchRequested("/api/live?watch=0"))
        assertFalse(liveUiWatchRequested("/api/live?watch=1&watch=1"))
        assertFalse(liveUiWatchRequested("/?watch=1"))
        assertFalse(liveUiWatchRequested("/health?watch=1"))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun socketConfigurationChecksDeadlinesCloseOnExecAndBlockingMode() = memScoped {
        val descriptors = allocArray<IntVar>(2)
        assertEquals(0, socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors))
        try {
            val client = descriptors[0]
            assertEquals(0, fcntl(client, platform.posix.F_SETFL, O_NONBLOCK))
            assertNull(configureLiveHttpSocket(client, LiveHttpSocketKind.CLIENT))
            assertTrue(fcntl(client, F_GETFD) and FD_CLOEXEC != 0)
            assertEquals(0, fcntl(client, F_GETFL) and O_NONBLOCK)
            LiveHttpAssertSocketBooleanOption(client, SO_NOSIGPIPE)
            LiveHttpAssertSocketTimeout(client, SO_RCVTIMEO)
            LiveHttpAssertSocketTimeout(client, SO_SNDTIMEO)

            val listener = descriptors[1]
            assertNull(configureLiveHttpSocket(listener, LiveHttpSocketKind.LISTENER))
            assertTrue(fcntl(listener, F_GETFD) and FD_CLOEXEC != 0)
            assertTrue(fcntl(listener, F_GETFL) and O_NONBLOCK != 0)
            LiveHttpAssertSocketBooleanOption(listener, SO_REUSEADDR)
            LiveHttpAssertSocketBooleanOption(listener, SO_NOSIGPIPE)
            LiveHttpAssertSocketTimeout(listener, SO_RCVTIMEO)
            LiveHttpAssertSocketTimeout(listener, SO_SNDTIMEO)
        } finally {
            close(descriptors[0])
            close(descriptors[1])
        }
    }

    @Test
    fun socketConfigurationReportsInjectedInvalidDescriptorFailure() {
        val failure = assertNotNull(
            configureLiveHttpSocket(-1, LiveHttpSocketKind.CLIENT),
        )

        assertContains(failure, "SO_NOSIGPIPE")
    }

    @Test
    fun probeFindsNothingListeningWhenTheConnectionIsRefused() {
        assertEquals(
            LiveUiEndpointState.NothingListens,
            classifyLiveUiProbe(LiveUiProbeOutcome(7, 0L, harmonHeader = false, error = "Couldn't connect to server")),
        )
    }

    @Test
    fun probeKeepsTimeoutsAndDroppedConnectionsAsNoAnswer() {
        for (curlCode in listOf(28, 52, 56)) {
            assertEquals(
                LiveUiEndpointState.NoAnswer("curl $curlCode"),
                classifyLiveUiProbe(LiveUiProbeOutcome(curlCode, 0L, harmonHeader = false, error = "curl $curlCode")),
            )
        }
    }

    @Test
    fun probeWithoutAnHttpStatusIsNoAnswer() {
        assertEquals(
            LiveUiEndpointState.NoAnswer("curl_easy_init failed"),
            classifyLiveUiProbe(LiveUiProbeOutcome(0, 0L, harmonHeader = false, error = "curl_easy_init failed")),
        )
    }

    @Test
    fun probeRespondsOnlyWithHttp200AndTheHarmonHeader() {
        assertEquals(
            LiveUiEndpointState.Responds,
            classifyLiveUiProbe(LiveUiProbeOutcome(0, 200L, harmonHeader = true, error = "")),
        )
        for (httpStatus in listOf(200L, 403L, 404L)) {
            assertEquals(
                LiveUiEndpointState.AnotherProcess(httpStatus),
                classifyLiveUiProbe(LiveUiProbeOutcome(0, httpStatus, harmonHeader = false, error = "")),
            )
        }
    }

    @Test
    fun launcherRemovesAnEndpointThatNothingListensOn() {
        val (fileSystem, store) = LiveUiLauncherPublishedStore()

        val failure = assertFailsWith<IllegalStateException> {
            LiveUiLauncher.open(store) {
                LiveUiProbeOutcome(7, 0L, harmonHeader = false, error = "Couldn't connect to server")
            }
        }

        assertContains(failure.message.orEmpty(), "not running")
        assertContains(failure.message.orEmpty(), "nothing listens on 127.0.0.1:49321")
        assertContains(failure.message.orEmpty(), "harmon status")
        assertFalse(fileSystem.isRegularFile(store.path))
    }

    @Test
    fun launcherRemovesAnEndpointThatAnotherProcessAnswers() {
        val (fileSystem, store) = LiveUiLauncherPublishedStore()

        val failure = assertFailsWith<IllegalStateException> {
            LiveUiLauncher.open(store) { LiveUiProbeOutcome(0, 404L, harmonHeader = false, error = "") }
        }

        assertContains(failure.message.orEmpty(), "not running")
        assertContains(failure.message.orEmpty(), "another process answered on 127.0.0.1:49321 with HTTP 404")
        assertContains(failure.message.orEmpty(), "harmon status")
        assertFalse(fileSystem.isRegularFile(store.path))
    }

    @Test
    fun launcherRemovesAnEndpointAnsweredWithoutTheHarmonHeader() {
        val (fileSystem, store) = LiveUiLauncherPublishedStore()

        val failure = assertFailsWith<IllegalStateException> {
            LiveUiLauncher.open(store) { LiveUiProbeOutcome(0, 200L, harmonHeader = false, error = "") }
        }

        assertContains(failure.message.orEmpty(), "not running")
        assertContains(
            failure.message.orEmpty(),
            "another process answered on 127.0.0.1:49321 with HTTP 200 but no X-Harmon-Live header",
        )
        assertContains(failure.message.orEmpty(), "harmon status")
        assertFalse(fileSystem.isRegularFile(store.path))
    }

    @Test
    fun launcherKeepsTheEndpointOfAnAgentThatDidNotAnswerInTime() {
        val (_, store) = LiveUiLauncherPublishedStore()

        val failure = assertFailsWith<IllegalStateException> {
            LiveUiLauncher.open(store) {
                LiveUiProbeOutcome(
                    28,
                    0L,
                    harmonHeader = false,
                    error = "Operation timed out after 2001 milliseconds",
                )
            }
        }

        assertContains(failure.message.orEmpty(), "did not answer on 127.0.0.1:49321 within 2 s")
        assertContains(failure.message.orEmpty(), "Operation timed out after 2001 milliseconds")
        assertContains(failure.message.orEmpty(), "try again")
        assertEquals(LIVE_UI_LAUNCHER_ENDPOINT, store.read())
    }
}

private val LIVE_UI_LAUNCHER_ENDPOINT = LiveUiEndpoint(49_321, "c".repeat(64))

private fun LiveUiLauncherPublishedStore(): Pair<InMemorySetupFileSystem, LiveUiEndpointStore> {
    val fileSystem = InMemorySetupFileSystem()
    val store = LiveUiEndpointStore("/fake/home", fileSystem)
    store.publish(LIVE_UI_LAUNCHER_ENDPOINT)
    return fileSystem to store
}

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.MemScope.LiveHttpAssertSocketBooleanOption(
    descriptor: Int,
    option: Int,
) {
    val value = alloc<IntVar>()
    val length = alloc<socklen_tVar>()
    length.value = sizeOf<IntVar>().convert()
    assertEquals(0, getsockopt(descriptor, SOL_SOCKET, option, value.ptr, length.ptr))
    assertTrue(value.value != 0)
}

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.MemScope.LiveHttpAssertSocketTimeout(descriptor: Int, option: Int) {
    val value = alloc<timeval>()
    val length = alloc<socklen_tVar>()
    length.value = sizeOf<timeval>().convert()
    assertEquals(0, getsockopt(descriptor, SOL_SOCKET, option, value.ptr, length.ptr))
    assertEquals(2L, value.tv_sec)
    assertEquals(0, value.tv_usec)
}
