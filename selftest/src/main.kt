import dev.yoda.harmon.nativebridge.probe.HMProcessIssue
import dev.yoda.harmon.nativebridge.probe.HMProcessSample
import dev.yoda.harmon.nativebridge.probe.HM_ATTRIBUTION_REGION_LIMIT
import dev.yoda.harmon.nativebridge.probe.hm_count_processes
import dev.yoda.harmon.nativebridge.probe.hm_list_processes
import dev.yoda.harmon.nativebridge.probe.hm_monotonic_time_ns
import dev.yoda.harmon.nativebridge.probe.hm_process_issue_size
import dev.yoda.harmon.nativebridge.probe.hm_process_sample_size
import dev.yoda.harmon.nativebridge.probe.hm_read_compressed_or_paged_out
import dev.yoda.harmon.nativebridge.probe.hm_read_physical_memory
import dev.yoda.harmon.nativebridge.probe.hm_uint32_counter
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


private var failures = 0
private var reported = 0
private var filter: String? = null

private var startedAtNanoseconds = 0uL

private const val LISTING_SLACK = 256

private const val THREAD_TIME_ALLOWANCE = 8uL
private const val SCHEDULING_ALLOWANCE = 16uL

private const val TIMEOUT_SECONDS = 60u

private fun selected(name: String): Boolean = filter?.let(name::startsWith) ?: true

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
    fflush(stdout)
}

@OptIn(ExperimentalForeignApi::class)
private fun reportUsage(program: String) {
    fputs("usage: $program [--self-check] [name-prefix]\n", stderr)
    fflush(stderr)
}

@OptIn(ExperimentalForeignApi::class)
private fun runBindingChecks() {
    checkCounterMapping()
    checkStructSizes()
    checkMonotonicClock()
    checkProcessSample()
    checkAttributionSelfWalk()
}

@OptIn(ExperimentalForeignApi::class)
private fun checkCounterMapping() {
    val wrapped = hm_uint32_counter(-1)
    check("binding.uint32-counter-wraps", wrapped == UInt.MAX_VALUE.toULong()) {
        "expected ${UInt.MAX_VALUE}, got $wrapped"
    }
}

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

@OptIn(ExperimentalForeignApi::class)
private fun checkMonotonicClock() {
    val first = hm_monotonic_time_ns()
    val second = hm_monotonic_time_ns()
    check("binding.monotonic-clock-advances", first > 0uL && second >= first) {
        "expected 0 < first <= second, got first=$first second=$second"
    }
}

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
        filter = null
        check("harness.self-check", false) {
            "deliberate failure that proves the fail branch runs"
        }
    }
    if (reported == 0) {
        println("ok   harness.no-checks-selected")
    }

    exitProcess(if (failures == 0) 0 else 1)
}
