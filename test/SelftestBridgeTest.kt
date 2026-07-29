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
 * Everything the selftest binary is built from: its own sources, the bridge it links, and the
 * three files that decide what "built" means.
 *
 * The module files earn their place: dropping the `nativebridge` dependency, moving to another
 * Kotlin version or turning off `allWarningsAsErrors` all change what gets linked without touching
 * a line of Kotlin, and a guard that watched sources alone would stay green over a binary produced
 * by the previous configuration.
 *
 * Not private, because `NativeHarnessTest` names it as the list its guard tests stand in for.
 */
val SELFTEST_SOURCES: List<String> = listOf(
    "selftest/src",
    "selftest/module.yaml",
    "nativebridge/cinterop/harmon_native.def",
    "nativebridge/module.yaml",
    "harmon.module-template.yaml",
)

/**
 * The binary, checked for staleness before it is run.
 *
 * Every test here needs both, and in this order: `./kotlin test` does not link `selftest`, so a
 * binary that is missing or older than its sources has to fail the test that was about to trust it.
 */
private fun currentSelftest(): NativeTool = selftestHarness().also {
    assertHarnessIsCurrent(it, SELFTEST_SOURCES)
}

/**
 * Runs the `selftest` binary through the same machinery as the C harness.
 *
 * The difference is that `./kotlin test` does not build this binary — `./kotlin build` does — so
 * every run is preceded by the staleness guard.
 */
class SelftestBridgeTest {
    @Test
    fun runsTheSelftestBinary() =
        assertHarnessSucceeded(runNativeHarness(currentSelftest()), SELFTEST_CHECKS)

    /** The same proof the C harness gets, through the same helper. */
    @Test
    fun reportsADeliberateFailure() =
        assertReportsDeliberateFailure(currentSelftest(), foreignFilter = "binding.")

    /**
     * A filter that matches nothing collides with the rule that an empty output means a harness
     * that died before reporting anything. Both harnesses resolve it the same way, by saying so out
     * loud instead of printing nothing.
     */
    @Test
    fun reportsThatAFilterSelectedNothing() {
        val run = runNativeHarness(currentSelftest(), listOf("no-such-suite."))

        assertEquals(0, run.exitCode, "selecting no checks is not a failure\n${run.describe()}")
        assertHarnessSucceeded(run, setOf("harness.no-checks-selected"))
    }

    /** A mistyped flag must be a usage error here too, not a filter that quietly matches nothing. */
    @Test
    fun rejectsAnUnknownFlag() = assertRejectsAnUnknownFlag(currentSelftest())
}
