package dev.yoda.harmon.webuitest

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveUiServerTest {
    @Test
    fun headerAuthRenewsOnlyExplicitWatchWhileQueryAuthRemainsProbeOnly() {
        Harness().use { harness ->
            assertEquals(0, harness.watchCount())

            val shell = liveHttpRequest(harness, "/")
            assertEquals(200, shell.statusCode)
            assertFalse("set-cookie" in shell.headers)

            assertEquals(
                200,
                liveHttpRequest(
                    harness,
                    "/api/live?token=${harness.token}",
                ).statusCode,
            )
            assertEquals(0, harness.watchCount())

            assertEquals(
                403,
                liveHttpRequest(
                    harness,
                    "/api/live?token=${harness.token}&watch=1",
                ).statusCode,
            )
            assertEquals(0, harness.watchCount())

            assertEquals(
                403,
                liveHttpRequest(
                    harness,
                    "/api/live?watch=1",
                    authorization = "Bearer ${"b".repeat(64)}",
                ).statusCode,
            )
            assertEquals(0, harness.watchCount())

            assertEquals(
                200,
                liveHttpRequest(
                    harness,
                    "/api/live",
                    authorization = "Bearer ${harness.token}",
                ).statusCode,
            )
            assertEquals(0, harness.watchCount())

            assertEquals(
                200,
                liveHttpRequest(
                    harness,
                    "/api/live?watch=1",
                    authorization = "Bearer ${harness.token}",
                ).statusCode,
            )
            assertEquals(1, harness.watchCount())

            val duplicateAuthorization = liveHttpRawRequest(
                harness,
                "GET /api/live?watch=1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${harness.port}\r\n" +
                    "Authorization: Bearer ${harness.token}\r\n" +
                    "Authorization: Bearer ${harness.token}\r\n\r\n",
            )
            assertEquals(403, duplicateAuthorization.statusCode)
            assertEquals(1, harness.watchCount())
        }
    }

    @Test
    fun hostMustBeExactlyOneCanonicalLoopbackAuthority() {
        Harness().use { harness ->
            assertEquals(
                200,
                liveHttpRawRequest(
                    harness,
                    "GET / HTTP/1.1\r\nhOsT:\t127.0.0.1:${harness.port} \t\r\n\r\n",
                ).statusCode,
            )
            assertEquals(
                400,
                liveHttpRawRequest(harness, "GET / HTTP/1.1\r\nAccept: text/html\r\n\r\n")
                    .statusCode,
            )
            assertEquals(
                400,
                liveHttpRawRequest(
                    harness,
                    "GET / HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${harness.port}\r\n" +
                        "HOST: 127.0.0.1:${harness.port}\r\n\r\n",
                ).statusCode,
            )
            assertEquals(
                421,
                liveHttpRawRequest(
                    harness,
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1:${harness.port + 1}\r\n\r\n",
                ).statusCode,
            )
            assertEquals(
                421,
                liveHttpRawRequest(
                    harness,
                    "GET / HTTP/1.1\r\nHost: localhost:${harness.port}\r\n\r\n",
                ).statusCode,
            )
        }
    }

    @Test
    fun stalledHeaderDoesNotDelayAHealthyConcurrentWorker() {
        Harness().use { harness ->
            liveHttpPartialClient(harness).use { slowClient ->
                slowClient.getOutputStream().apply {
                    write('G'.code)
                    flush()
                }
                Thread.sleep(150)

                val startedAt = System.nanoTime()
                val response = liveHttpRequest(
                    harness,
                    "/api/live",
                    authorization = "Bearer ${harness.token}",
                )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                assertEquals(200, response.statusCode)
                assertTrue(elapsedMillis < 1_500, "healthy request completed in $elapsedMillis ms")
            }
        }
    }

    @Test
    fun dripFedHeaderExpiresAgainstOneAbsoluteDeadline() {
        Harness().use { harness ->
            liveHttpPartialClient(harness).use { socket ->
                val output = socket.getOutputStream()
                val drip = thread(isDaemon = true, name = "live-http-header-drip") {
                    try {
                        repeat(16) {
                            output.write(if (it == 0) 'G'.code else ' '.code)
                            output.flush()
                            Thread.sleep(250)
                        }
                    } catch (_: Exception) {
                    }
                }
                val startedAt = System.nanoTime()
                try {
                    socket.getInputStream().readBytes()
                } catch (_: SocketException) {
                }
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                assertTrue(elapsedMillis in 1_500..3_000, "drip-fed request ended in $elapsedMillis ms")
                drip.join(1_000)
            }
        }
    }

    @Test
    fun ninthConcurrentClientIsRejectedInsteadOfQueued() {
        Harness().use { harness ->
            val stalled = List(8) {
                liveHttpPartialClient(harness).also { socket ->
                    socket.getOutputStream().apply {
                        write('G'.code)
                        flush()
                    }
                }
            }
            try {
                Thread.sleep(300)
                val startedAt = System.nanoTime()
                val ninth = liveHttpRequest(
                    harness,
                    "/api/live",
                    authorization = "Bearer ${harness.token}",
                )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                assertNull(ninth.statusCode, "ninth client unexpectedly received ${ninth.statusCode}")
                assertTrue(elapsedMillis < 1_000, "ninth client was retained for $elapsedMillis ms")
            } finally {
                stalled.forEach { it.close() }
            }
            Thread.sleep(150)
            assertEquals(
                200,
                liveHttpRequest(
                    harness,
                    "/api/live",
                    authorization = "Bearer ${harness.token}",
                ).statusCode,
            )
        }
    }

    @Test
    fun stopWaitReleasesLifecycleLockForWorkerCompletion() {
        Harness().use { harness ->
            harness.setWatchDelay(600)
            val request = thread(isDaemon = true, name = "live-http-delayed-watch") {
                liveHttpRequest(
                    harness,
                    "/api/live?watch=1",
                    authorization = "Bearer ${harness.token}",
                )
            }
            liveHttpAwaitWatchCount(harness, 1)

            val startedAt = System.nanoTime()
            harness.restart()
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(elapsedMillis < 2_000, "stop waited $elapsedMillis ms")
            request.join(2_000)
            assertFalse(request.isAlive, "worker did not finish while stop waited")
            harness.setWatchDelay(0)
            assertEquals(
                200,
                liveHttpRequest(
                    harness,
                    "/api/live",
                    authorization = "Bearer ${harness.token}",
                ).statusCode,
            )
        }
    }

    @Test
    fun rapidGenerationReplacementDoesNotLetOldCleanupTouchNewSockets() {
        Harness().use { harness ->
            val startedAt = System.nanoTime()
            repeat(40) {
                liveHttpPartialClient(harness).use { oldClient ->
                    oldClient.getOutputStream().apply {
                        write('G'.code)
                        flush()
                    }
                    harness.restart()
                }
                assertEquals(
                    200,
                    liveHttpRequest(
                        harness,
                        "/api/live",
                        authorization = "Bearer ${harness.token}",
                    ).statusCode,
                )
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMillis < 15_000, "40 generations took $elapsedMillis ms")
        }
    }

    @Test
    fun injectedAcceptedSocketSetupFailureClosesTheDescriptor() {
        Harness().use { harness ->
            harness.rejectAcceptedClients(true)
            val rejected = liveHttpRequest(
                harness,
                "/api/live",
                authorization = "Bearer ${harness.token}",
            )
            assertNull(rejected.statusCode)

            harness.rejectAcceptedClients(false)
            assertEquals(
                200,
                liveHttpRequest(
                    harness,
                    "/api/live",
                    authorization = "Bearer ${harness.token}",
                ).statusCode,
            )
        }
    }
}

private data class LiveHttpTestResponse(
    val statusCode: Int?,
    val headers: Map<String, List<String>>,
)

private fun liveHttpRequest(
    harness: Harness,
    target: String,
    authorization: String? = null,
): LiveHttpTestResponse {
    val authorizationLine = authorization?.let { "Authorization: $it\r\n" }.orEmpty()
    return liveHttpRawRequest(
        harness,
        "GET $target HTTP/1.1\r\n" +
            "Host: 127.0.0.1:${harness.port}\r\n" +
            authorizationLine +
            "Connection: close\r\n\r\n",
    )
}

private fun liveHttpRawRequest(harness: Harness, request: String): LiveHttpTestResponse {
    val raw = try {
        liveHttpPartialClient(harness).use { socket ->
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        }
    } catch (_: SocketException) {
        ""
    }
    val lines = raw.substringBefore("\r\n\r\n").split("\r\n")
    val status = lines.firstOrNull()
        ?.takeIf { it.startsWith("HTTP/1.1 ") }
        ?.substringAfter("HTTP/1.1 ")
        ?.substringBefore(' ')
        ?.toIntOrNull()
    val headers = lines.drop(1)
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).lowercase() to
                line.substring(separator + 1).trim()
        }
        .groupBy({ it.first }, { it.second })
    return LiveHttpTestResponse(status, headers)
}

private fun liveHttpPartialClient(harness: Harness): Socket = Socket().apply {
    soTimeout = 3_000
    connect(InetSocketAddress("127.0.0.1", harness.port), 3_000)
}

private fun liveHttpAwaitWatchCount(harness: Harness, expected: Int) {
    repeat(40) {
        if (harness.watchCount() >= expected) return
        Thread.sleep(25)
    }
    assertEquals(expected, harness.watchCount())
}
