package dev.yoda.harmon.webuitest

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.assertions.PlaywrightAssertions
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.fail

class Harness : AutoCloseable {
    private val process: Process
    private val stdin: BufferedWriter
    private val stdout = LinkedBlockingQueue<String>()
    private val stderr = StringBuilder()
    private val readers: List<Thread>

    val port: Int
    val token: String
    val snapshotUrl: String
    val baseUrl: String

    init {
        configureAssertions()
        installBrowserOnce()
        process = ProcessBuilder(
            harnessBinary().toString(),
            "--exit-after-ms=$WATCHDOG_MILLIS",
        )
            .apply {
                directory(repoRoot.toFile())
                redirectErrorStream(false)
                environment()["http_proxy"] = "http://127.0.0.1:1"
                environment()["ALL_PROXY"] = "http://127.0.0.1:1"
                environment()["NO_PROXY"] = ""
            }
            .start()
        stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        readers = listOf(
            drain("webuicheck-stdout", process.inputStream) { stdout.put(it) },
            drain("webuicheck-stderr", process.errorStream) { line ->
                synchronized(stderr) { stderr.appendLine(line) }
            },
        )

        port = handshake("PORT=").toIntOrNull()
            ?: harnessFailure("PORT handshake is not numeric")
        token = handshake("TOKEN=")
        val snapshotPath = handshake("SNAPSHOT=")
        if (nextLine() != "READY") harnessFailure("missing READY handshake")
        baseUrl = "http://127.0.0.1:$port"
        snapshotUrl = Path.of(snapshotPath).toUri().toString()
    }

    fun liveUrl(): String = "$baseUrl/?token=$token"

    fun send(command: String) {
        require('\n' !in command && '\r' !in command)
        stdin.write(command)
        stdin.newLine()
        stdin.flush()
    }

    fun sendAndWait(command: String) {
        send(command)
        val acknowledgement = nextLine()
        if (acknowledgement != "ACK=$command") {
            harnessFailure("expected command acknowledgement, got '$acknowledgement'")
        }
    }

    fun watchCount(): Int {
        send("watch count")
        val response = nextLine()
        if (!response.startsWith("WATCH=")) {
            harnessFailure("expected WATCH response, got '$response'")
        }
        return response.removePrefix("WATCH=").toIntOrNull()
            ?: harnessFailure("WATCH response is not numeric")
    }

    override fun close() {
        stdin.close()
        if (!process.waitFor(SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(KILL_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(KILL_MILLIS, TimeUnit.MILLISECONDS)
            }
            harnessFailure("webuicheck did not stop after stdin closed")
        }
        readers.forEach { it.join(1_000) }
        if (process.exitValue() != 0) harnessFailure("webuicheck exited with ${process.exitValue()}")
    }

    private fun handshake(prefix: String): String {
        val line = nextLine()
        if (!line.startsWith(prefix)) harnessFailure("expected $prefix handshake, got '$line'")
        return line.removePrefix(prefix)
    }

    private fun nextLine(): String = stdout.poll(HANDSHAKE_MILLIS, TimeUnit.MILLISECONDS)
        ?: harnessFailure("timed out waiting for webuicheck handshake")

    private fun harnessFailure(message: String): Nothing {
        val diagnostics = synchronized(stderr) { stderr.toString() }.ifBlank { "(no stderr)" }
        fail("$message\nwebuicheck stderr:\n$diagnostics")
    }
}

fun onChromium(block: (Browser) -> Unit) {
    Playwright.create().use { playwright ->
        playwright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(System.getenv(HEADED_ENV) == null),
        ).use(block)
    }
}

fun onWebKit(block: (Browser) -> Unit) {
    Playwright.create().use { playwright ->
        playwright.webkit().launch(
            BrowserType.LaunchOptions().setHeadless(System.getenv(HEADED_ENV) == null),
        ).use(block)
    }
}

fun Browser.desktopContext(): BrowserContext = newContext(
    Browser.NewContextOptions().setViewportSize(1440, 900),
)

fun BrowserContext.traced(name: String, block: () -> Unit) {
    Files.createDirectories(resultsDirectory)
    tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
    var failed = false
    try {
        block()
    } catch (failure: Throwable) {
        failed = true
        try {
            pages().lastOrNull()?.screenshot(
                Page.ScreenshotOptions()
                    .setPath(resultsDirectory.resolve("$name.png"))
                    .setFullPage(true),
            )
        } catch (_: Throwable) {
        }
        throw failure
    } finally {
        if (failed) {
            tracing().stop(Tracing.StopOptions().setPath(resultsDirectory.resolve("$name.zip")))
        } else {
            tracing().stop()
        }
    }
}

private fun drain(name: String, stream: InputStream, line: (String) -> Unit): Thread =
    Thread { stream.bufferedReader(StandardCharsets.UTF_8).forEachLine(line) }.apply {
        isDaemon = true
        this.name = name
        start()
    }

private fun harnessBinary(): Path {
    val candidates = listOf(
        "build/tasks/_webuicheck_linkMacosArm64Debug/webuicheck.kexe",
        "build/tasks/_webuicheck_linkMacosArm64Release/webuicheck.kexe",
    ).map(repoRoot::resolve)
    return candidates.firstOrNull { Files.isExecutable(it) }
        ?: fail("webuicheck is missing; run ./kotlin build before browser tests")
}

private fun configureAssertions() {
    if (assertionsConfigured.compareAndSet(false, true)) {
        PlaywrightAssertions.setDefaultAssertionTimeout(ASSERTION_MILLIS)
    }
}

private fun installBrowserOnce() {
    if (!browserInstalled.compareAndSet(false, true)) return
    Playwright.create().close()
}

private val repoRoot: Path by lazy {
    var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    while (directory != null) {
        if (Files.isRegularFile(directory.resolve("project.yaml"))) return@lazy directory
        directory = directory.parent
    }
    fail("could not locate the Harmon checkout")
}

private val resultsDirectory: Path by lazy { repoRoot.resolve("webuitest/test-results") }
private val assertionsConfigured = AtomicBoolean(false)
private val browserInstalled = AtomicBoolean(false)

private const val HEADED_ENV = "HARMON_WEBUITEST_HEADED"
private const val WATCHDOG_MILLIS = 300_000L
private const val HANDSHAKE_MILLIS = 30_000L
private const val SHUTDOWN_MILLIS = 10_000L
private const val KILL_MILLIS = 3_000L
private const val ASSERTION_MILLIS = 15_000.0
