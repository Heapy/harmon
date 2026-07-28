import dev.yoda.harmon.nativebridge.hm_saturating_add_u64
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fputs
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
}

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
