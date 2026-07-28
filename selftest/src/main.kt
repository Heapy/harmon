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
import dev.yoda.harmon.nativebridge.hm_saturating_add_u64
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
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.stderr
import kotlin.system.exitProcess

/*
 * The Kotlin half of the native harness.
 *
 * It speaks the same line protocol as the C harness in `test/native`, so one bridge in
 * `test/NativeHarness.kt` reads both:
 *
 *     ok   binding.check-name
 *     fail binding.check-name: expected 3, got 4
 *
 * Usage: selftest.kexe [--self-check] [name-prefix]
 *
 * The exit code is 0 when every executed check passed and 1 otherwise; a second positional
 * argument is a usage error and exits with 2. `--self-check` adds a check that always fails, so
 * that the `fail` branch is executed by the suite itself rather than only by a real regression.
 *
 * Unlike the C harness this one prints `ok harness.no-checks-selected` when the filter matched
 * nothing: the bridge treats an empty output as a harness that died before reporting anything,
 * and a filter that selects nothing must not look like that.
 */

private var failures = 0
private var reported = 0
private var filter: String? = null

/** A check runs only when it matches the prefix filter, if one was given. */
private fun selected(name: String): Boolean = filter?.let(name::startsWith) ?: true

/**
 * Reports one check.
 *
 * [detail] is a lambda because it is printed only for a failure and regularly reads values that
 * are meaningless — or expensive to format — when the condition holds.
 */
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
}

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
 */
@OptIn(ExperimentalForeignApi::class)
private fun runBindingChecks() {
    val sum = hm_saturating_add_u64(1uL, 2uL)
    check("binding.bridge-is-linked", sum == 3uL) { "expected 3, got $sum" }

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
        val capacity = maxOf(hm_count_processes() + LISTING_HEADROOM, MIN_LISTING_CAPACITY)
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
    var ownFootprint: ULong? = null
    for (index in 0..<written) {
        val sample = samples[index]
        if (sample.pid <= 0) {
            return "sample $index of $written has pid ${sample.pid}"
        }
        if (sample.name.toKString().isEmpty()) {
            return "sample $index of $written (pid ${sample.pid}) has an empty name"
        }
        if (sample.pid == self) {
            ownFootprint = sample.physical_footprint_bytes
        }
    }

    val footprint = ownFootprint
        ?: return "the listing of $written processes does not contain this process (pid $self)"
    if (footprint == 0uL || footprint >= physicalMemory) {
        return "own footprint is $footprint bytes, expected between 1 and $physicalMemory"
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

private const val LISTING_HEADROOM = 256
private const val MIN_LISTING_CAPACITY = 512

fun main(args: Array<String>) {
    var selfCheck = false
    for (argument in args) {
        when {
            argument == "--self-check" -> selfCheck = true
            filter == null -> filter = argument
            else -> {
                reportUsage("selftest")
                exitProcess(2)
            }
        }
    }

    runBindingChecks()

    if (selfCheck) {
        check("harness.self-check", false) {
            "deliberate failure that proves the fail branch runs"
        }
    }
    if (reported == 0) {
        println("ok   harness.no-checks-selected")
    }

    exitProcess(if (failures == 0) 0 else 1)
}
