package dev.yoda.harmon.webuitest

import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveUiServerTest {
    @Test
    fun onlyAnAuthenticatedExplicitWatchRenewsTheLease() {
        Harness().use { harness ->
            val client = HttpClient.newHttpClient()

            fun get(path: String): Int {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(harness.baseUrl + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build()
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            }

            assertEquals(0, harness.watchCount())
            assertEquals(200, get("/api/live?token=${harness.token}"))
            assertEquals(200, get("/?token=${harness.token}"))
            assertEquals(0, harness.watchCount())

            assertEquals(403, get("/api/live?token=${"b".repeat(64)}&watch=1"))
            assertEquals(0, harness.watchCount())

            assertEquals(200, get("/api/live?token=${harness.token}&watch=1"))
            assertEquals(1, harness.watchCount())
        }
    }

    @Test
    fun slowHeaderCannotHoldTheOnlyAcceptQueuePastTheAbsoluteDeadline() {
        Harness().use { harness ->
            Socket("127.0.0.1", harness.port).use { slowClient ->
                val output = slowClient.getOutputStream()
                output.write('G'.code)
                output.flush()
                val drip = thread(isDaemon = true, name = "slow-live-ui-client") {
                    try {
                        repeat(40) {
                            Thread.sleep(250)
                            output.write(' '.code)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    }
                }
                Thread.sleep(250)

                val request = HttpRequest.newBuilder()
                    .uri(URI.create("${harness.baseUrl}/api/live?token=${harness.token}"))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build()
                val startedAt = System.nanoTime()
                val response = HttpClient.newHttpClient().send(
                    request,
                    HttpResponse.BodyHandlers.discarding(),
                )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                assertEquals(200, response.statusCode())
                assertTrue(elapsedMillis in 1_000..5_000, "request completed in $elapsedMillis ms")
                drip.join(1_000)
            }
        }
    }
}
