import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every check `selftest.kexe` is expected to run, for the same reason the C harness has such a
 * list: a check that stops being executed would otherwise vanish without a line turning red.
 */
private val SELFTEST_CHECKS = setOf(
    "binding.bridge-is-linked",
)

/**
 * Runs the `selftest` binary through the same machinery as the C harness.
 *
 * The difference is that `./kotlin test` does not build this binary — `./kotlin build` does — so
 * every run is preceded by the staleness guard.
 */
class SelftestBridgeTest {
    @Test
    fun runsTheSelftestBinary() {
        val tool = selftestHarness()
        assertHarnessIsCurrent(tool, SELFTEST_SOURCES)

        assertHarnessSucceeded(runNativeHarness(tool), SELFTEST_CHECKS)
    }

    /**
     * The same proof the C harness gets: without `--self-check` the `fail` branch never executes,
     * so a `check` broken into always reporting `ok` would keep the suite green forever.
     */
    @Test
    fun reportsADeliberateFailure() {
        val tool = selftestHarness()
        assertHarnessIsCurrent(tool, SELFTEST_SOURCES)

        val run = runNativeHarness(tool, listOf("--self-check", "harness."))

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
    }

    /**
     * A filter that matches nothing collides with the rule that an empty output means a harness
     * that died before reporting anything. The harness resolves it by saying so out loud instead
     * of printing nothing.
     */
    @Test
    fun reportsThatAFilterSelectedNothing() {
        val tool = selftestHarness()
        assertHarnessIsCurrent(tool, SELFTEST_SOURCES)

        val run = runNativeHarness(tool, listOf("no-such-suite."))

        assertEquals(0, run.exitCode, "selecting no checks is not a failure\n${run.describe()}")
        assertHarnessSucceeded(run, setOf("harness.no-checks-selected"))
    }
}
