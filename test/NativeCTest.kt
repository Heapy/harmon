import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every check `scripts/test-native.sh` is expected to run.
 *
 * The set is spelled out rather than derived from the output: a check that stops being executed —
 * because a suite lost its call, or a `#if` swallowed it — would otherwise disappear without a
 * single line turning red. The price is that a new check is a two-file change: the `CHECK` call and
 * its name here, or the run fails as `unexpected`.
 */
private val C_HARNESS_CHECKS = setOf(
    "pure.saturating-add-zero",
    "pure.saturating-add-adds",
    "pure.saturating-add-reaches-max",
    "pure.saturating-add-clamps-by-one",
    "pure.saturating-add-clamps-both-max",
    "pure.saturating-multiply-by-zero",
    "pure.saturating-multiply-by-one",
    "pure.saturating-multiply-reaches-max",
    "pure.saturating-multiply-clamps",
    "pure.uint32-counter-zero",
    "pure.uint32-counter-wraps-minus-one",
    "pure.uint32-counter-int32-min",
    "pure.uint32-counter-int32-max",
    "pure.list-capacity-ignores-failed-count",
    "pure.list-capacity-uses-output-when-count-is-lower",
    "pure.list-capacity-uses-count-when-it-is-higher",
    "pure.list-capacity-clamps-at-maximum",
    "pure.list-capacity-holds-the-minimum",
    "pure.candidates-order-by-footprint",
    "pure.candidates-tie-breaks-by-index",
    "pure.candidates-sort-descends",
    "pure.candidates-sort-keeps-tie-order",
    "pure.mach-time-matches-uptime-clock",
    "pure.mach-time-converts-zero",
    "pure.discard-http-response-consumes-everything",
    "attribution.self-walk-completes",
    "attribution.consumed-is-reported",
    "attribution.dead-pid-not-measured",
    "attribution.vanishing-pid-is-an-undercount",
    "attribution.region-limit-undercount",
    "attribution.rejects-invalid-arguments",
    "processes.listing-is-consistent",
    "processes.total-matches-a-fresh-count",
    "processes.samples-are-well-formed",
    "processes.issues-are-well-formed",
    "processes.rejects-invalid-arguments",
    "snapshot.memory-and-load-are-plausible",
    "snapshot.processor-counters-never-go-backwards",
    "snapshot.swap-and-virtual-memory-readable",
    "snapshot.storage-and-battery-readable",
    "framing.send-rejects-null",
    "framing.send-rejects-empty",
    "framing.send-rejects-oversized",
    "framing.round-trips-a-frame",
    "framing.receive-accepts-a-null-size",
    "framing.receive-rejects-oversized-length",
    "framing.receive-rejects-length-above-maximum",
    "framing.receive-rejects-zero-length",
    "framing.receive-rejects-embedded-nul",
    "framing.receive-frees-rejected-frame",
    "framing.receive-rejects-truncated-header",
    "framing.receive-rejects-truncated-payload",
    "framing.receive-assembles-split-payload",
    "framing.send-completes-partial-write",
    "socket.rejects-bad-path",
    "socket.refuses-foreign-occupant",
    "socket.replaces-stale-socket",
    "socket.mode-is-0660",
    "socket.connect-rejects-bad-path",
    "socket.connect-to-live-and-missing",
    "socket.accept-returns-peer-credentials",
    "socket.accept-rejects-foreign-uid",
    "socket.remove-handles-bad-input",
)

class NativeCTest {
    @Test
    fun runsTheCHarness() {
        assertHarnessSucceeded(runNativeHarness(cTestHarness()), C_HARNESS_CHECKS)
    }

    @Test
    fun reportsADeliberateFailure() = assertReportsDeliberateFailure(cTestHarness())

    /**
     * The same sentinel `selftest` prints, and for the same reason: the bridge reads an output with
     * no check line in it as a harness that died before reporting anything, so a filter that
     * selected nothing has to say so rather than exit quietly.
     */
    @Test
    fun reportsThatAFilterSelectedNothing() {
        val run = runNativeHarness(cTestHarness(), listOf("no-such-suite."))

        assertEquals(0, run.exitCode, "selecting no checks is not a failure\n${run.describe()}")
        assertHarnessSucceeded(run, setOf("harness.no-checks-selected"))
    }

    /**
     * A mistyped flag must not be taken for a name filter: it would match nothing, print the
     * sentinel and exit 0 — a green run of a harness that checked nothing at all.
     */
    @Test
    fun rejectsAnUnknownFlag() = assertRejectsAnUnknownFlag(cTestHarness())
}
