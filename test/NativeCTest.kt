import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every check `scripts/test-native.sh` is expected to run.
 *
 * The set is spelled out rather than derived from the output: a check that stops being executed —
 * because a suite lost its call, or a `#if` swallowed it — would otherwise disappear without a
 * single line turning red.
 */
private val C_HARNESS_CHECKS = setOf(
    "pure.saturating-add-adds",
)

class NativeCTest {
    @Test
    fun runsTheCHarness() {
        assertHarnessSucceeded(runNativeHarness(cTestHarness()), C_HARNESS_CHECKS)
    }

    /**
     * Without `--self-check` the `fail` branch of the C harness never executes, so a `CHECK` macro
     * broken into always reporting `ok` would keep the suite green forever. The flag forces one
     * deliberate failure; the prefix filter keeps the other suites out of this run.
     */
    @Test
    fun reportsADeliberateFailure() {
        val run = runNativeHarness(cTestHarness(), listOf("--self-check", "harness."))

        assertEquals(1, run.exitCode, "a failing check must leave a non-zero exit code")
        assertEquals(
            1,
            run.checks.size,
            "the filter must select exactly the deliberate failure\n${run.describe()}",
        )
        val check = run.checks.single()
        assertEquals("harness.self-check", check.name)
        assertFalse(check.passed, "the deliberate check must be reported as failed")
        assertTrue(check.detail.isNotEmpty(), "a failure must carry a detail")

        val reported = assertFailsWith<AssertionError> {
            assertHarnessSucceeded(run, setOf("harness.self-check"))
        }
        assertTrue(
            reported.message.orEmpty().contains("harness.self-check"),
            "the assertion must name the check that failed, got: ${reported.message}",
        )
    }
}
