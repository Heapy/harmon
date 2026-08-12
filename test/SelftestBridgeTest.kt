import kotlin.test.Test
import kotlin.test.assertEquals

private val SELFTEST_CHECKS = setOf(
    "attribution.self-walk-completes",
    "binding.monotonic-clock-advances",
    "binding.process-sample-readable",
    "binding.struct-sizes-agree",
    "binding.uint32-counter-wraps",
)

val SELFTEST_SOURCES: List<String> = listOf(
    "selftest/src",
    "selftest/module.yaml",
    "bridge-probe/cinterop/harmon_probe.def",
    "bridge-probe/module.yaml",
    "harmon.module-template.yaml",
)

private fun currentSelftest(): NativeTool = selftestHarness().also {
    assertHarnessIsCurrent(it, SELFTEST_SOURCES)
}

class SelftestBridgeTest {
    @Test
    fun runsTheSelftestBinary() =
        assertHarnessSucceeded(runNativeHarness(currentSelftest()), SELFTEST_CHECKS)

    @Test
    fun reportsADeliberateFailure() =
        assertReportsDeliberateFailure(currentSelftest(), foreignFilter = "binding.")

    @Test
    fun reportsThatAFilterSelectedNothing() {
        val run = runNativeHarness(currentSelftest(), listOf("no-such-suite."))

        assertEquals(0, run.exitCode, "selecting no checks is not a failure\n${run.describe()}")
        assertHarnessSucceeded(run, setOf("harness.no-checks-selected"))
    }

    @Test
    fun rejectsAnUnknownFlag() = assertRejectsAnUnknownFlag(currentSelftest())
}
