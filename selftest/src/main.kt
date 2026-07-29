import dev.yoda.harmon.nativebridge.HMProcessIssue
import dev.yoda.harmon.nativebridge.HMProcessSample
import dev.yoda.harmon.nativebridge.HM_ATTRIBUTION_REGION_LIMIT
import dev.yoda.harmon.nativebridge.hm_count_processes
import dev.yoda.harmon.nativebridge.hm_list_processes
import dev.yoda.harmon.nativebridge.hm_monotonic_time_ns
import dev.yoda.harmon.nativebridge.hm_process_issue_size
import dev.yoda.harmon.nativebridge.hm_process_sample_size
import dev.yoda.harmon.nativebridge.hm_read_compressed_or_paged_out
import dev.yoda.harmon.nativebridge.hm_read_physical_memory
import dev.yoda.harmon.nativebridge.hm_uint32_counter
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.alarm
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.stderr
import platform.posix.stdout
import kotlin.system.exitProcess

/*
 * The Kotlin half of the native harness.
 *
 * It speaks the same line protocol as the C harness in `test/native`, so one bridge in
 * `test/NativeHarness.kt` reads both. The protocol is described once, in the "How the native layer
 * is tested" section of CLAUDE.md; in short:
 *
 *     ok   binding.check-name
 *     fail binding.check-name: expected 3, got 4
 *
 * Usage: selftest.kexe [--self-check] [name-prefix]
 */

private var failures = 0
private var reported = 0
private var filter: String? = null

/**
 * When this run started, read by the CPU-time bound in [implausibleOwnSample].
 *
 * The wall clock the check needs is this process's lifetime, and nothing in the bridge reports it,
 * so `main` records the reading it takes before anything else.
 */
private var startedAtNanoseconds = 0uL

/**
 * Slack over the process count, so that the listing is wide enough to contain this process.
 *
 * Nothing here has to agree with what the collector reserves: the check needs one particular
 * process in the sample, and the bridge widens its own intermediate PID list regardless.
 */
private const val LISTING_SLACK = 256

/**
 * How far the process's own CPU time may sit from its wall-clock lifetime, in either direction.
 *
 * Bounds rather than measurements, and two-sided because a mach timebase applied the wrong way
 * round is an *under*count: on Apple Silicon it is 125/3, so a transposed one reports a
 * fortieth of the time rather than forty times it. Between the start of `main` and this check
 * `selftest` does nothing but list processes, which is CPU-bound — the measured ratio is 0.75 to
 * 0.93 over five runs — so both bounds keep an order of magnitude of room: [THREAD_TIME_ALLOWANCE]
 * for the runtime's own threads above, [SCHEDULING_ALLOWANCE] for a machine that deschedules this
 * one below.
 */
private const val THREAD_TIME_ALLOWANCE = 8uL
private const val SCHEDULING_ALLOWANCE = 16uL

/**
 * How long a whole run may take before the alarm ends it, which is `HM_TEST_TIMEOUT_SECONDS` in
 * `test/native/harness.h` — the two harnesses answer the same promise in CLAUDE.md and there is no
 * shared place to put one constant, so each names the other.
 *
 * `runNativeHarness` blocks in `fgets` for as long as the child lives, so a walk or a listing that
 * hung inside the kernel would hang `./kotlin test` with it, with no output to explain why. The
 * signal turns that into a run the bridge reports as an abnormal termination.
 */
private const val TIMEOUT_SECONDS = 60u

/** A check runs only when it matches the prefix filter, if one was given. */
private fun selected(name: String): Boolean = filter?.let(name::startsWith) ?: true

/**
 * Reports one check.
 *
 * [detail] is a lambda because it is printed only for a failure and regularly reads values that
 * are meaningless — or expensive to format — when the condition holds.
 */
@OptIn(ExperimentalForeignApi::class)
private fun check(name: String, condition: Boolean, detail: () -> String) {
    if (!selected(name)) {
        return
    }
    reported++
    if (condition) {
        println("ok   $name")
    } else {
        failures++
        println("fail $name: ${detail()}")
    }
    /*
     * Flushed per line, as `hm_test_report` in the C harness is. Standard output is a pipe under
     * the bridge, so a run killed by the alarm would otherwise report nothing at all about how far
     * it got — which is the one case the alarm exists for.
     */
    fflush(stdout)
}

/** Kotlin/Native leaves `argv[0]` out of `args`, so the name is written out rather than read. */
@OptIn(ExperimentalForeignApi::class)
private fun reportUsage(program: String) {
    fputs("usage: $program [--self-check] [name-prefix]\n", stderr)
    fflush(stderr)
}

/**
 * The checks that cross the Kotlin/C seam.
 *
 * Anything provable from plain C belongs in `test/native` instead: those tests need no build of
 * this module and no staleness guard.
 *
 * Unlike `main.c`, the filter gates reporting only and not execution. There are five checks and the
 * two that cost anything — a full listing and a region walk — are the reason this binary exists, so
 * there is no equivalent of a suite that forks children or opens sockets for output nobody selected.
 */
@OptIn(ExperimentalForeignApi::class)
private fun runBindingChecks() {
    checkCounterMapping()
    checkStructSizes()
    checkMonotonicClock()
    checkProcessSample()
    checkAttributionSelfWalk()
}

/**
 * `uint32_t` counters are widened through `hm_uint32_counter` before they cross the seam.
 *
 * The kernel reports fault and context-switch counters as 32-bit values that wrap, and Kotlin has
 * no unsigned C integer of its own to receive them in: read as a signed `Int` a wrapped counter
 * turns negative, and the rate computed from it turns into a large negative spike. `-1` is the
 * value that separates the two readings.
 */
@OptIn(ExperimentalForeignApi::class)
private fun checkCounterMapping() {
    val wrapped = hm_uint32_counter(-1)
    check("binding.uint32-counter-wraps", wrapped == UInt.MAX_VALUE.toULong()) {
        "expected ${UInt.MAX_VALUE}, got $wrapped"
    }
}

/** Both struct sizes, as Kotlin models them and as the C compiler lays them out. */
@OptIn(ExperimentalForeignApi::class)
private fun checkStructSizes() {
    val sampleInKotlin = sizeOf<HMProcessSample>().toULong()
    val sampleInC = hm_process_sample_size()
    val issueInKotlin = sizeOf<HMProcessIssue>().toULong()
    val issueInC = hm_process_issue_size()

    check(
        "binding.struct-sizes-agree",
        sampleInKotlin == sampleInC && issueInKotlin == issueInC,
    ) {
        "HMProcessSample is $sampleInKotlin bytes in Kotlin and $sampleInC in C; " +
            "HMProcessIssue is $issueInKotlin and $issueInC"
    }
}

/**
 * Two readings of the monotonic clock, in order.
 *
 * A `uint64_t` misread as a signed value, or truncated to 32 bits, shows up here as a zero or as
 * a second reading behind the first — the sampling intervals are computed from this difference.
 */
@OptIn(ExperimentalForeignApi::class)
private fun checkMonotonicClock() {
    val first = hm_monotonic_time_ns()
    val second = hm_monotonic_time_ns()
    check("binding.monotonic-clock-advances", first > 0uL && second >= first) {
        "expected 0 < first <= second, got first=$first second=$second"
    }
}

/**
 * A real listing read through cinterop, checked against what the process knows about itself.
 *
 * A struct layout that drifted apart would not fail to compile — it would hand Kotlin the wrong
 * field at the right offset — so the assertion has to be about values that could not be anything
 * else: a positive pid, a name the bridge guarantees to be non-empty, and this very process
 * carrying a footprint somewhere between one byte and the memory installed in the machine.
 * That catches a shifted layout more reliably than comparing `sizeOf` against a hard-coded
 * number, `HM_PROCESS_PATH_SIZE` being an SDK constant rather than one of ours.
 */
@OptIn(ExperimentalForeignApi::class)
private fun checkProcessSample() {
    memScoped {
        val capacity = maxOf(hm_count_processes(), 0) + LISTING_SLACK
        val samples = allocArray<HMProcessSample>(capacity)
        val issues = allocArray<HMProcessIssue>(capacity)
        val total = alloc<IntVar>()
        val inaccessible = alloc<IntVar>()
        val writtenIssues = alloc<IntVar>()
        val physicalMemory = alloc<ULongVar>()
        val memoryStatus = hm_read_physical_memory(physicalMemory.ptr)

        /* Attribution is switched off: its own walk is the check below, and over a full listing
         * it would cost seconds. */
        val written = hm_list_processes(
            samples,
            capacity,
            issues,
            capacity,
            0,
            0,
            total.ptr,
            inaccessible.ptr,
            writtenIssues.ptr,
        )

        val problem = when {
            written <= 0 -> "hm_list_processes returned $written for $capacity slots"
            memoryStatus != 0 -> "hw.memsize is unreadable, so no footprint bound exists"
            else -> implausibleSample(samples, written, physicalMemory.value)
        }
        check("binding.process-sample-readable", problem == null) { problem.orEmpty() }
    }
}

/** What is wrong with the first sample that does not hold up, or `null` when all of them do. */
@OptIn(ExperimentalForeignApi::class)
private fun implausibleSample(
    samples: CArrayPointer<HMProcessSample>,
    written: Int,
    physicalMemory: ULong,
): String? {
    val self = getpid()
    var own: HMProcessSample? = null
    for (index in 0..<written) {
        val sample = samples[index]
        if (sample.pid <= 0) {
            return "sample $index of $written has pid ${sample.pid}"
        }
        if (sample.name.toKString().isEmpty()) {
            return "sample $index of $written (pid ${sample.pid}) has an empty name"
        }
        /*
         * The kernel reports these as 32-bit counters and `hm_uint32_counter` widens them; a
         * widening that sign-extended instead would put a wrapped counter far above this bound.
         */
        if (sample.faults > UInt.MAX_VALUE.toULong() ||
            sample.context_switches > UInt.MAX_VALUE.toULong()
        ) {
            return "sample $index of $written (pid ${sample.pid}) carries ${sample.faults} faults " +
                "and ${sample.context_switches} context switches, both bounded by ${UInt.MAX_VALUE}"
        }
        if (sample.pid == self) {
            own = sample
        }
    }

    val ownSample = own
        ?: return "the listing of $written processes does not contain this process (pid $self)"
    return implausibleOwnSample(ownSample, physicalMemory)
}

/**
 * What is wrong with this process's own sample, where the expected values are known.
 *
 * The fields most likely to survive a layout drift that keeps `sizeOf` intact are the ones nothing
 * else looks at: the two times come out of `hm_mach_time_to_ns`, and the counters out of
 * `hm_uint32_counter`. The CPU time is bounded by the wall clock this process has been alive for,
 * read from CLOCK_MONOTONIC rather than from the mach timebase, so a conversion off by the Apple
 * Silicon factor of 125/3 overshoots it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun implausibleOwnSample(sample: HMProcessSample, physicalMemory: ULong): String? {
    val footprint = sample.physical_footprint_bytes
    if (footprint == 0uL || footprint >= physicalMemory) {
        return "own footprint is $footprint bytes, expected between 1 and $physicalMemory"
    }

    val elapsed = hm_monotonic_time_ns() - startedAtNanoseconds
    val cpuTime = sample.user_time_ns + sample.system_time_ns
    if (cpuTime > elapsed * THREAD_TIME_ALLOWANCE || cpuTime < elapsed / SCHEDULING_ALLOWANCE) {
        return "own cpu time is $cpuTime ns over $elapsed ns of wall clock, expected between " +
            "a ${SCHEDULING_ALLOWANCE}th of the elapsed time and $THREAD_TIME_ALLOWANCE times it"
    }
    if (sample.context_switches == 0uL || sample.faults == 0uL) {
        return "own counters are ${sample.context_switches} context switches and " +
            "${sample.faults} faults, both expected above zero"
    }
    return null
}

/**
 * The same walk `test/native` performs, deliberately repeated here.
 *
 * The address space of a Kotlin/Native process holds far more regions than that of a 30-kilobyte
 * C binary, so the walk covers more ground; and a walk that reads a `struct proc_regioninfo` per
 * region is the binding check in its most meaningful form.
 */
@OptIn(ExperimentalForeignApi::class)
private fun checkAttributionSelfWalk() {
    memScoped {
        val bytes = alloc<ULongVar>()
        val regions = alloc<IntVar>()
        val consumed = alloc<IntVar>()
        val status = hm_read_compressed_or_paged_out(
            getpid(),
            HM_ATTRIBUTION_REGION_LIMIT,
            bytes.ptr,
            regions.ptr,
            consumed.ptr,
        )

        check(
            "attribution.self-walk-completes",
            status == 0 && regions.value > 0 && consumed.value >= regions.value,
        ) {
            "expected status 0 over a non-empty address space, got status $status over " +
                "${regions.value} regions in ${consumed.value} calls"
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    startedAtNanoseconds = hm_monotonic_time_ns()
    var selfCheck = false
    for (argument in args) {
        when {
            argument == "--self-check" -> selfCheck = true
            /* Why a dash is a usage error: CLAUDE.md, the protocol paragraph. */
            argument.startsWith("-") || filter != null -> {
                reportUsage("selftest")
                exitProcess(2)
            }

            else -> filter = argument
        }
    }

    alarm(TIMEOUT_SECONDS)

    runBindingChecks()

    if (selfCheck) {
        /*
         * Why the filter is dropped before the deliberate failure: CLAUDE.md, the protocol
         * paragraph. Nothing after this point reads the filter.
         */
        filter = null
        check("harness.self-check", false) {
            "deliberate failure that proves the fail branch runs"
        }
    }
    /* Why an empty selection still prints a line: CLAUDE.md, the protocol paragraph. */
    if (reported == 0) {
        println("ok   harness.no-checks-selected")
    }

    exitProcess(if (failures == 0) 0 else 1)
}
