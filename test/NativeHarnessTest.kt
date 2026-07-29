import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdtemp
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MISSING_HARNESS = "scripts/no-such-harness.sh"

/**
 * Enough of a gap for two files created in a row to carry distinguishable timestamps.
 *
 * The guard compares sub-second times, so this is generous rather than necessary; it costs the
 * three guard tests a twentieth of a second between them and removes the question entirely.
 */
private const val TIMESTAMP_GAP_MICROSECONDS = 20_000u

/**
 * The C harness under a relative path that does not exist, which is what every test here wants: a
 * tool nothing has to build, whose resolved path and override key are the subject.
 */
private fun missingHarness(override: String? = null): NativeTool = NativeTool(
    label = "a harness",
    environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
    relativePath = MISSING_HARNESS,
    override = override,
)

private fun harnessRun(
    lines: List<String>,
    exitCode: Int? = 0,
    signal: Int? = null,
): NativeHarnessRun = NativeHarnessRun(
    tool = missingHarness(),
    command = "'harness'",
    lines = lines,
    exitCode = exitCode,
    signal = signal,
)

private fun toolAt(path: String): NativeTool = NativeTool(
    label = "the selftest binary",
    environmentKey = "HARMON_SELFTEST_BIN",
    relativePath = path,
    override = null,
)

/**
 * A scratch directory under `/tmp` rather than `TMPDIR`, matching the C socket suite: the per-user
 * `TMPDIR` on macOS is long enough to matter and nothing here needs it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun temporaryDirectory(): String = memScoped {
    /* `cstr` allocates a writable copy in this scope, which is what mkdtemp edits in place. */
    mkdtemp("/tmp/harmon-guard-test.XXXXXX".cstr.getPointer(this))?.toKString()
        ?: fail("cannot create a temporary directory")
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(path: String, content: String = "harmon\n") {
    val file = fopen(path, "w") ?: fail("cannot create $path")
    fputs(content, file)
    fclose(file)
}

/** A harness of one `printf`: the only way to drive [runNativeHarness] over chosen output. */
@OptIn(ExperimentalForeignApi::class)
private fun scriptPrinting(root: String, body: String): NativeTool {
    val path = "$root/harness.sh"
    writeFile(path, "#!/bin/sh\n$body\n")
    chmod(path, "755".toUShort(radix = 8))
    return NativeTool(
        label = "a scripted harness",
        environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
        relativePath = path,
        override = null,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun createDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

/** Runs [body] against a scratch directory that is removed whatever the outcome. */
@OptIn(ExperimentalForeignApi::class)
private fun withTemporaryDirectory(body: (String) -> Unit) {
    val root = temporaryDirectory()
    try {
        body(root)
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }
}

class NativeHarnessTest {
    @Test
    fun readsAPassingLine() {
        assertEquals(
            NativeCheck("attribution.self-walk-completes", passed = true, detail = ""),
            parseNativeCheck("ok   attribution.self-walk-completes"),
        )
    }

    @Test
    fun readsAFailingLineWithItsDetail() {
        assertEquals(
            NativeCheck(
                name = "attribution.region-limit-undercount",
                passed = false,
                detail = "expected available=0, got 1",
            ),
            parseNativeCheck(
                "fail attribution.region-limit-undercount: expected available=0, got 1",
            ),
        )
    }

    /**
     * Only the first colon separates the name from the detail: a detail is free-form C `printf`
     * output and carries colons of its own often enough that splitting on the last one, or on all
     * of them, would truncate the very message that explains the failure.
     */
    @Test
    fun keepsColonsInsideTheDetail() {
        assertEquals(
            NativeCheck(
                name = "socket.accept-rejects-foreign-uid",
                passed = false,
                detail = "expected -2: EACCES, got -1: EPERM",
            ),
            parseNativeCheck(
                "fail socket.accept-rejects-foreign-uid: expected -2: EACCES, got -1: EPERM",
            ),
        )
    }

    @Test
    fun ignoresEmptyAndUnrecognisedLines() {
        listOf(
            "",
            "   ",
            "ok",
            "fail",
            "okay done",
            "clang: error: no such file or directory",
            "harmon-native-test(41252,0x1f0) malloc: double free",
        ).forEach { line ->
            assertNull(parseNativeCheck(line), "expected noise, got a check from: $line")
        }
    }

    @Test
    fun decodesHowAProcessEnded() {
        assertEquals(0, harnessExitCode(0))
        assertEquals(1, harnessExitCode(1 shl 8))
        assertEquals(2, harnessExitCode(2 shl 8))
        assertNull(harnessSignal(0))
        assertNull(harnessSignal(1 shl 8))

        assertEquals(6, harnessSignal(6), "SIGABRT must be read as a signal, not as an exit code")
        assertNull(harnessExitCode(6))
    }

    @Test
    fun reportsEveryFailingCheckByName() {
        val failure = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(
                    lines = listOf(
                        "ok   pure.saturating-add-adds",
                        "fail pure.uint32-counter-wraps: expected 4294967295, got 0",
                    ),
                    exitCode = 1,
                ),
                setOf("pure.saturating-add-adds", "pure.uint32-counter-wraps"),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("pure.uint32-counter-wraps: expected 4294967295"),
            "the assertion must carry the text of the failing check, got: ${failure.message}",
        )
    }

    /**
     * The assertion ends the test on the first failure, so the rest would go unmentioned unless
     * they were named in its text — and "one of five checks failed" is a worse message than a list.
     */
    @Test
    fun namesTheOtherFailingChecksAfterTheFirst() {
        val failure = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(
                    lines = listOf(
                        "fail pure.uint32-counter-wraps: expected 4294967295, got 0",
                        "ok   pure.saturating-add-adds",
                        "fail socket.mode-is-0660: expected 0660, got 0666",
                    ),
                    exitCode = 1,
                ),
                setOf(
                    "pure.uint32-counter-wraps",
                    "pure.saturating-add-adds",
                    "socket.mode-is-0660",
                ),
            )
        }

        val message = failure.message.orEmpty()
        assertTrue(
            message.contains("pure.uint32-counter-wraps: expected 4294967295"),
            "the first failure must carry its own text, got: $message",
        )
        assertTrue(
            message.contains("also failing: socket.mode-is-0660"),
            "every other failure must be named, got: $message",
        )
    }

    /**
     * A harness that dies halfway through prints nothing but `ok` lines, so the exit status is the
     * only thing that separates a completed run from a truncated one.
     */
    @Test
    fun refusesARunThatDidNotFinishNormally() {
        val killed = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(
                    lines = listOf("ok   pure.saturating-add-adds"),
                    exitCode = null,
                    signal = 14,
                ),
                setOf("pure.saturating-add-adds"),
            )
        }
        assertTrue(
            killed.message.orEmpty().contains("signal 14"),
            "an abnormal termination must name the signal, got: ${killed.message}",
        )

        val exited = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(lines = listOf("ok   pure.saturating-add-adds"), exitCode = 2),
                setOf("pure.saturating-add-adds"),
            )
        }
        assertTrue(
            exited.message.orEmpty().contains("exited with 2"),
            "a non-zero exit must be reported, got: ${exited.message}",
        )
    }

    @Test
    fun refusesARunThatReportedNothing() {
        val failure = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(lines = emptyList()),
                setOf("pure.saturating-add-adds"),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("no checks at all"),
            "an empty output must fail rather than pass vacuously, got: ${failure.message}",
        )
    }

    @Test
    fun noticesACheckThatStoppedBeingRun() {
        val failure = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(lines = listOf("ok   pure.saturating-add-adds")),
                setOf("pure.saturating-add-adds", "pure.uint32-counter-wraps"),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("pure.uint32-counter-wraps"),
            "a check that vanished must be named, got: ${failure.message}",
        )
    }

    @Test
    fun noticesACheckThatIsNotOnTheExpectedList() {
        val failure = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(
                harnessRun(lines = listOf("ok   pure.saturating-add-adds", "ok   pure.brand-new")),
                setOf("pure.saturating-add-adds"),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("pure.brand-new"),
            "a new check must be added to the expected list, got: ${failure.message}",
        )
    }

    /**
     * The test process runs with the module directory as its working directory, which is what makes
     * a relative path resolve at all; that assumption breaks under `--build-dir`, so the resolved
     * absolute path travels in every message and the environment key overrides it.
     */
    @Test
    fun resolvesToolPathsAbsolutely() {
        val tool = missingHarness()

        assertEquals("${currentDirectory()}/$MISSING_HARNESS", tool.path)
        assertEquals(
            "/opt/harmon/harness.sh",
            missingHarness(override = "/opt/harmon/harness.sh").path,
            "an override must win over the relative default",
        )
        assertEquals(
            tool.path,
            missingHarness(override = "  ").path,
            "a blank override must be treated as absent",
        )
    }

    /**
     * A missing binary fails the test rather than skipping it: `./kotlin test` does not build the
     * external harnesses, so a silent skip is exactly how this coverage would rot away unnoticed.
     */
    @Test
    fun failsWithTheAbsolutePathWhenTheToolIsMissing() {
        val tool = missingHarness()

        val failure = assertFailsWith<AssertionError> { runNativeHarness(tool) }

        assertTrue(
            failure.message.orEmpty().contains(tool.path),
            "the absolute resolved path must be in the message, got: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("HARMON_NATIVE_TEST_SCRIPT"),
            "the override key must be in the message, got: ${failure.message}",
        )
    }

    /**
     * `./kotlin test` does not link the selftest binary, so its absence means `./kotlin build` has
     * not run — and that has to be a failure with instructions, never a skip.
     */
    @Test
    fun failsWhenTheHarnessBinaryWasNeverBuilt() {
        val tool = toolAt("build/tasks/_selftest_linkMacosArm64Debug/no-such-selftest.kexe")

        val failure = assertFailsWith<AssertionError> {
            assertHarnessIsCurrent(tool, SELFTEST_SOURCES)
        }

        assertTrue(
            failure.message.orEmpty().contains("./kotlin build"),
            "the message must say how to fix it, got: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains(tool.path),
            "the absolute resolved path must be in the message, got: ${failure.message}",
        )
    }

    /**
     * The binary is only as good as the sources it was linked from, and nothing in `./kotlin test`
     * relinks it. An edit that was never built must fail rather than be measured by yesterday's
     * binary.
     */
    @Test
    fun failsWhenTheBinaryIsOlderThanItsSources() = withTemporaryDirectory { root ->
        val binary = "$root/selftest.kexe"
        writeFile(binary)
        usleep(TIMESTAMP_GAP_MICROSECONDS)
        createDirectory("$root/src")
        writeFile("$root/src/main.kt")

        val failure = assertFailsWith<AssertionError> {
            assertHarnessIsCurrent(toolAt(binary), listOf("$root/src"))
        }

        assertTrue(
            failure.message.orEmpty().contains("main.kt"),
            "the message must name the source that moved ahead, got: ${failure.message}",
        )
    }

    /**
     * The tree is walked, not stat'ed at the top: a directory's own timestamp does not move when
     * the contents of a file inside it change, so an edit nested one level down would otherwise
     * pass unnoticed.
     */
    @Test
    fun noticesAnEditNestedInTheSourceTree() = withTemporaryDirectory { root ->
        createDirectory("$root/src/binding")
        writeFile("$root/src/main.kt")
        val binary = "$root/selftest.kexe"
        writeFile(binary)
        usleep(TIMESTAMP_GAP_MICROSECONDS)
        writeFile("$root/src/binding/checks.kt")

        val failure = assertFailsWith<AssertionError> {
            assertHarnessIsCurrent(toolAt(binary), listOf("$root/src"))
        }

        assertTrue(
            failure.message.orEmpty().contains("binding"),
            "the message must name the nested source, got: ${failure.message}",
        )
    }

    /**
     * A source list that resolves to nothing is how the guard stops guarding: a path renamed in the
     * repository but not in [SELFTEST_SOURCES] leaves a list of files that do not exist, and a
     * guard that took "no sources" for "nothing newer" would pass over any binary at all.
     */
    @Test
    fun failsWhenNoSourceExists() = withTemporaryDirectory { root ->
        val binary = "$root/selftest.kexe"
        writeFile(binary)

        val failure = assertFailsWith<AssertionError> {
            assertHarnessIsCurrent(toolAt(binary), listOf("$root/gone", "$root/also-gone.def"))
        }

        assertTrue(
            failure.message.orEmpty().contains("none of the sources"),
            "an empty source list must fail rather than pass, got: ${failure.message}",
        )
    }

    /**
     * `fgets` fills a fixed buffer, so a line longer than it arrives in pieces that have to be
     * joined, and a last line without a newline has to be kept rather than dropped. Both are how a
     * real check name reaches the parser when a C `printf` is interleaved with anything else.
     */
    @Test
    fun assemblesLinesSplitAcrossReadsAndWithoutATrailingNewline() = withTemporaryDirectory { root ->
        val detail = "x".repeat(9000)
        val tool = scriptPrinting(
            root,
            """
            echo "fail pure.long-detail: $detail"
            printf 'ok   pure.no-trailing-newline'
            """.trimIndent(),
        )

        val run = runNativeHarness(tool)

        assertEquals(
            listOf("pure.long-detail", "pure.no-trailing-newline"),
            run.checks.map { it.name },
            "both lines must survive the read buffer\n${run.describe()}",
        )
        assertEquals(detail, run.checks.first().detail, "a split line must be joined verbatim")
        assertTrue(run.checks.last().passed, "a line without a newline is still a check")
    }

    @Test
    fun acceptsABinaryNewerThanEverySource() = withTemporaryDirectory { root ->
        createDirectory("$root/src/binding")
        writeFile("$root/src/main.kt")
        writeFile("$root/src/binding/checks.kt")
        val definition = "$root/harmon_native.def"
        writeFile(definition)
        usleep(TIMESTAMP_GAP_MICROSECONDS)
        val binary = "$root/selftest.kexe"
        writeFile(binary)

        assertHarnessIsCurrent(toolAt(binary), listOf("$root/src", definition))

        assertEquals(
            definition,
            newestSource(definition)?.path,
            "a source that is a plain file must be read directly, not enumerated",
        )
    }
}
