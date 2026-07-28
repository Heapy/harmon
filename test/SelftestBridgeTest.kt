import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every check `selftest.kexe` is expected to run, for the same reason the C harness has such a
 * list: a check that stops being executed would otherwise vanish without a line turning red. A new
 * check is therefore a two-file change — the `check` call and its name here.
 */
private val SELFTEST_CHECKS = setOf(
    "attribution.self-walk-completes",
    "binding.monotonic-clock-advances",
    "binding.process-sample-readable",
    "binding.struct-sizes-agree",
    "binding.uint32-counter-wraps",
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

    /** The same proof the C harness gets, through the same helper. */
    @Test
    fun reportsADeliberateFailure() {
        val tool = selftestHarness()
        assertHarnessIsCurrent(tool, SELFTEST_SOURCES)

        assertReportsDeliberateFailure(tool)
    }

    /**
     * A filter that matches nothing collides with the rule that an empty output means a harness
     * that died before reporting anything. Both harnesses resolve it the same way, by saying so out
     * loud instead of printing nothing.
     */
    @Test
    fun reportsThatAFilterSelectedNothing() {
        val tool = selftestHarness()
        assertHarnessIsCurrent(tool, SELFTEST_SOURCES)

        val run = runNativeHarness(tool, listOf("no-such-suite."))

        assertEquals(0, run.exitCode, "selecting no checks is not a failure\n${run.describe()}")
        assertHarnessSucceeded(run, setOf("harness.no-checks-selected"))
    }

    /** A mistyped flag must be a usage error here too, not a filter that quietly matches nothing. */
    @Test
    fun rejectsAnUnknownFlag() {
        val tool = selftestHarness()
        assertHarnessIsCurrent(tool, SELFTEST_SOURCES)

        assertRejectsAnUnknownFlag(tool)
    }
}
