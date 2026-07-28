import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MISSING_HARNESS = "scripts/no-such-harness.sh"

private fun harnessRun(
    lines: List<String>,
    exitCode: Int? = 0,
    signal: Int? = null,
): NativeHarnessRun = NativeHarnessRun(
    tool = NativeTool(
        label = "a harness",
        environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
        relativePath = MISSING_HARNESS,
        override = null,
    ),
    command = "'harness'",
    lines = lines,
    exitCode = exitCode,
    signal = signal,
)

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
        val tool = NativeTool(
            label = "a harness",
            environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
            relativePath = MISSING_HARNESS,
            override = null,
        )

        assertEquals("${currentDirectory()}/$MISSING_HARNESS", tool.path)
        assertEquals(
            "/opt/harmon/harness.sh",
            NativeTool(
                label = "a harness",
                environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
                relativePath = MISSING_HARNESS,
                override = "/opt/harmon/harness.sh",
            ).path,
            "an override must win over the relative default",
        )
        assertEquals(
            tool.path,
            NativeTool(
                label = "a harness",
                environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
                relativePath = MISSING_HARNESS,
                override = "  ",
            ).path,
            "a blank override must be treated as absent",
        )
    }

    /**
     * A missing binary fails the test rather than skipping it: `./kotlin test` does not build the
     * external harnesses, so a silent skip is exactly how this coverage would rot away unnoticed.
     */
    @Test
    fun failsWithTheAbsolutePathWhenTheToolIsMissing() {
        val tool = NativeTool(
            label = "a harness",
            environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
            relativePath = MISSING_HARNESS,
            override = null,
        )

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
}
