import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.timeIntervalSince1970
import platform.posix.PATH_MAX
import platform.posix.X_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fgets
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.strerror
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * The bridge from `kotlin.test` to the external harnesses.
 *
 * KTC-5573 keeps the cinterop klib out of the test compilation, so the native bridge is exercised
 * by separate binaries — the C harness built by `scripts/test-native.sh` and `selftest` — that
 * speak one line-based protocol, described once in the "How the native layer is tested" section of
 * CLAUDE.md:
 *
 *     ok   suite.check-name
 *     fail suite.check-name: expected 3, got 4
 *
 * Parsing that output is not enough on its own: a harness that dies halfway through prints nothing
 * but `ok` lines and would pass. So every run is also checked for a normal exit, for a non-empty
 * output, and for the full set of check names the suite is expected to run.
 */

/** One reported check: its name, whether it passed, and the detail a failure printed after it. */
data class NativeCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/**
 * A check line, or `null` for anything else.
 *
 * The harness shares its stream with whatever the shell, the compiler, or the C runtime decides to
 * print, so an unrecognised line is noise rather than an error; the run as a whole is judged by its
 * exit status, and the noise is quoted only when something else already failed.
 */
fun parseNativeCheck(line: String): NativeCheck? {
    val text = line.trim()
    val separator = text.indexOfFirst { it == ' ' || it == '\t' }
    if (separator < 0) {
        return null
    }
    val remainder = text.substring(separator).trim()
    if (remainder.isEmpty()) {
        return null
    }
    return when (text.substring(0, separator)) {
        "ok" -> NativeCheck(name = remainder, passed = true, detail = "")
        "fail" -> failedCheck(remainder)
        else -> null
    }
}

/**
 * The detail of a failure is free-form and regularly contains colons of its own ("expected 3:4"),
 * so only the first one separates the name from the detail.
 */
private fun failedCheck(remainder: String): NativeCheck {
    val colon = remainder.indexOf(':')
    if (colon < 0) {
        return NativeCheck(name = remainder, passed = false, detail = "")
    }
    return NativeCheck(
        name = remainder.substring(0, colon),
        passed = false,
        detail = remainder.substring(colon + 1).trim(),
    )
}

/** The exit code of a `pclose` status, or `null` when the process did not exit on its own. */
fun harnessExitCode(status: Int): Int? =
    if (status and 0x7f == 0) (status shr 8) and 0xff else null

/** The signal that killed a `pclose` status, or `null` when the process exited on its own. */
fun harnessSignal(status: Int): Int? {
    val termination = status and 0x7f
    return if (termination == 0 || termination == 0x7f) null else termination
}

/**
 * An external harness binary and where to find it.
 *
 * `NativeTestTask` runs the test process with the module directory — the project root — as its
 * working directory, which is what makes a relative [relativePath] resolve at all. That assumption
 * breaks under `--build-dir` and `--project-dir`, so the path is resolved to an absolute one up
 * front, every message quotes it, and [environmentKey] overrides it.
 */
class NativeTool(
    val label: String,
    val environmentKey: String,
    relativePath: String,
    override: String? = readEnvironment(environmentKey),
) {
    val path: String = absolutePath(override?.takeIf { it.isNotBlank() } ?: relativePath)

    /** Text every failure of this tool ends with, so a wrong working directory is visible. */
    fun location(): String =
        "resolved to $path (working directory ${currentDirectory()}, " +
            "override with $environmentKey)"
}

/** The C harness: sources under `test/native`, compiled and run by the script on every call. */
fun cTestHarness(): NativeTool = NativeTool(
    label = "the C harness",
    environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
    relativePath = "scripts/test-native.sh",
)

/**
 * The `selftest` binary, which only `./kotlin build` produces.
 *
 * The path is tied to the debug variant deliberately: after `./kotlin build --variant release`
 * the debug binary stays where it was, and the staleness guard is what notices.
 */
fun selftestHarness(): NativeTool = NativeTool(
    label = "the selftest binary",
    environmentKey = "HARMON_SELFTEST_BIN",
    relativePath = "build/tasks/_selftest_linkMacosArm64Debug/selftest.kexe",
)

/**
 * Everything the selftest binary is built from: its own sources, the bridge it links, and the
 * three files that decide what "built" means.
 *
 * The module files earn their place: dropping the `nativebridge` dependency, moving to another
 * Kotlin version or turning off `allWarningsAsErrors` all change what gets linked without touching
 * a line of Kotlin, and a guard that watched sources alone would stay green over a binary produced
 * by the previous configuration.
 */
val SELFTEST_SOURCES: List<String> = listOf(
    "selftest/src",
    "selftest/module.yaml",
    "nativebridge/cinterop/harmon_native.def",
    "nativebridge/module.yaml",
    "harmon.module-template.yaml",
)

/** A file a harness is built from, and when it last changed. */
data class HarnessSource(val path: String, val modifiedAt: Double)

/**
 * When [path] last changed, or `null` when it does not exist.
 *
 * Seconds since the epoch as a `Double`, because `NSDate` keeps the sub-second part of the file
 * system timestamp — which is the only thing separating a binary from a source edited moments
 * before it.
 */
@OptIn(ExperimentalForeignApi::class)
fun modificationTime(path: String): Double? =
    (
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?.get(NSFileModificationDate) as? NSDate
        )?.timeIntervalSince1970

/**
 * Whether [path] is a directory, and therefore something to walk rather than to read directly.
 *
 * `enumeratorAtPath` cannot answer this: handed a plain file it returns an enumerator that yields
 * nothing at all, which is indistinguishable from an empty directory and would drop the `.def`
 * from the guard entirely.
 */
@OptIn(ExperimentalForeignApi::class)
private fun isDirectory(path: String): Boolean =
    NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?.get(NSFileType) == NSFileTypeDirectory

/**
 * The most recently changed file at [root], which may be a single file or a directory tree.
 *
 * Directories are walked rather than stat'ed: the modification time of a directory does not move
 * when the contents of a file inside it change, so looking at the directory alone would miss an
 * ordinary edit — the very case this guard exists for.
 */
@OptIn(ExperimentalForeignApi::class)
fun newestSource(root: String): HarnessSource? {
    if (!isDirectory(root)) {
        return modificationTime(root)?.let { HarnessSource(root, it) }
    }
    val enumerator = NSFileManager.defaultManager.enumeratorAtPath(root) ?: return null

    val entries = mutableListOf<HarnessSource>()
    while (true) {
        val entry = enumerator.nextObject() as? String ?: break
        val path = "$root/$entry"
        modificationTime(path)?.let { entries += HarnessSource(path, it) }
    }
    return entries.maxByOrNull { it.modifiedAt }
}

/**
 * Fails unless [tool] exists and is newer than every file under [sources].
 *
 * `./kotlin test` links the test binary and nothing else, so an external harness is whatever the
 * last `./kotlin build` happened to leave behind: possibly nothing, possibly a binary from before
 * the change under test. Both have to fail rather than skip — a silent skip is exactly how this
 * coverage would rot away without a single line turning red.
 */
fun assertHarnessIsCurrent(tool: NativeTool, sources: List<String>) {
    val built = modificationTime(tool.path)
        ?: fail("${tool.label} has not been built; run `./kotlin build` first: ${tool.location()}")

    val roots = sources.map(::absolutePath)
    val newest = roots.mapNotNull(::newestSource).maxByOrNull { it.modifiedAt }
        ?: fail("none of the sources of ${tool.label} exist: $roots; ${tool.location()}")

    if (built < newest.modifiedAt) {
        fail(
            "${tool.label} is older than ${newest.path}; run `./kotlin build` again: " +
                tool.location(),
        )
    }
}

/** What a run produced: its lines, the checks parsed out of them, and how the process ended. */
class NativeHarnessRun(
    val tool: NativeTool,
    val command: String,
    val lines: List<String>,
    val exitCode: Int?,
    val signal: Int?,
) {
    val checks: List<NativeCheck> = lines.mapNotNull(::parseNativeCheck)

    /** Lines that are not checks — compiler diagnostics, crash reports, anything on stderr. */
    val noise: List<String> = lines.filter { it.isNotBlank() && parseNativeCheck(it) == null }

    fun describe(): String {
        val ending = signal?.let { "killed by signal $it" } ?: "exited with code $exitCode"
        return "$command $ending; ${tool.location()}" +
            if (noise.isEmpty()) "" else noise.joinToString("\n", prefix = "\noutput:\n")
    }
}

/**
 * Runs [tool] and collects its output.
 *
 * Standard error is folded into the stream on purpose: when the script fails to compile, the clang
 * diagnostics are the only useful thing to report, and they arrive as [NativeHarnessRun.noise].
 */
@OptIn(ExperimentalForeignApi::class)
fun runNativeHarness(
    tool: NativeTool,
    arguments: List<String> = emptyList(),
): NativeHarnessRun {
    if (access(tool.path, X_OK) != 0) {
        fail("${tool.label} is not an executable file: ${tool.location()}")
    }

    val command = (listOf(tool.path) + arguments).joinToString(" ", postfix = " 2>&1") {
        shellQuote(it)
    }
    val stream = popen(command, "r")
        ?: fail("cannot start ${tool.label}: ${systemError()}; ${tool.location()}")

    val lines = mutableListOf<String>()
    val pending = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(READ_BUFFER_BYTES)
        while (fgets(buffer, READ_BUFFER_BYTES, stream) != null) {
            pending.append(buffer.toKString())
            var newline = pending.indexOf('\n')
            while (newline >= 0) {
                lines += pending.substring(0, newline)
                pending.deleteRange(0, newline + 1)
                newline = pending.indexOf('\n')
            }
        }
    }
    if (pending.isNotEmpty()) {
        lines += pending.toString()
    }

    val status = pclose(stream)
    if (status == -1) {
        fail("cannot wait for ${tool.label}: ${systemError()}; ${tool.location()}")
    }
    return NativeHarnessRun(
        tool = tool,
        command = command,
        lines = lines,
        exitCode = harnessExitCode(status),
        signal = harnessSignal(status),
    )
}

/**
 * Turns a run into assertions.
 *
 * The first failing check becomes the assertion, carrying its own text, so what breaks the build is
 * `attribution.dead-pid-not-measured` rather than "the native suite"; the others are named after it
 * because an `AssertionError` ends the test either way. The remaining conditions cover the ways a
 * harness can look green without having run: a crash, a non-zero exit with nothing printed, an
 * output with no check line in it at all — with or without a filter, which is why a filter that
 * selects nothing makes both harnesses print `harness.no-checks-selected` — or a check that quietly
 * stopped being executed.
 */
fun assertHarnessSucceeded(run: NativeHarnessRun, expectedChecks: Set<String>) {
    val failures = run.checks.filterNot { it.passed }
    failures.firstOrNull()?.let { check ->
        val alsoFailing = failures.drop(1).joinToString(", ") { it.name }
        fail(
            "${run.tool.label} reported ${check.name}: ${check.detail}" +
                (if (alsoFailing.isEmpty()) "" else " (also failing: $alsoFailing)") +
                "\n${run.describe()}",
        )
    }

    run.signal?.let { signal ->
        fail("${run.tool.label} died on signal $signal\n${run.describe()}")
    }
    if (run.exitCode != 0) {
        fail("${run.tool.label} exited with ${run.exitCode}\n${run.describe()}")
    }
    if (run.checks.isEmpty()) {
        fail("${run.tool.label} reported no checks at all\n${run.describe()}")
    }

    val reported = run.checks.map { it.name }.toSet()
    val missing = expectedChecks - reported
    val unexpected = reported - expectedChecks
    if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
        fail(
            "the checks ${run.tool.label} ran differ from the expected list" +
                (if (missing.isEmpty()) "" else "\nmissing: ${missing.sorted()}") +
                (if (unexpected.isEmpty()) "" else "\nunexpected: ${unexpected.sorted()}") +
                "\n${run.describe()}",
        )
    }
}

/**
 * Runs [tool] with `--self-check` and requires the deliberate failure to come back as one.
 *
 * Without the flag the `fail` branch of a harness never executes, so a `check` broken into always
 * reporting `ok` would keep the suite green forever. The prefix filter keeps the real suites out of
 * the run, and the last step is what proves the bridge turns a native failure into an
 * `AssertionError` that names the check rather than into a nameless "the native suite failed".
 *
 * [foreignFilter] is a filter that selects a real suite and never the deliberate failure, which is
 * the combination that used to swallow it: reported through the ordinary filter, the failure was
 * dropped and the run exited 0, so the flag answered "self-check passed" from a harness whose
 * `fail` branch had not run.
 */
fun assertReportsDeliberateFailure(tool: NativeTool, foreignFilter: String) {
    assertSurvivesAForeignFilter(tool, foreignFilter)

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

    val reported = assertFailsWith<AssertionError> {
        assertHarnessSucceeded(run, setOf("harness.self-check"))
    }
    assertTrue(
        reported.message.orEmpty().contains("harness.self-check"),
        "the assertion must name the check that failed, got: ${reported.message}",
    )
}

/**
 * `--self-check` under a filter that selects another suite: the failure must still be reported.
 *
 * The flag exists to execute the `fail` branch, so a filter that hides it turns the whole flag into
 * a lie — an all-green report and exit 0 from a run that proved nothing.
 */
private fun assertSurvivesAForeignFilter(tool: NativeTool, foreignFilter: String) {
    val run = runNativeHarness(tool, listOf("--self-check", foreignFilter))

    assertEquals(
        1,
        run.exitCode,
        "the deliberate failure must survive the filter $foreignFilter\n${run.describe()}",
    )
    val deliberate = run.checks.singleOrNull { it.name == "harness.self-check" }
        ?: fail("$foreignFilter hid the deliberate failure\n${run.describe()}")
    assertFalse(deliberate.passed, "the deliberate check must be reported as failed")
    assertTrue(
        run.checks.any { it.name.startsWith(foreignFilter) },
        "the filtered suite must still run\n${run.describe()}",
    )
}

/**
 * Runs [tool] with arguments that look like flags and are not, and requires a usage error for each.
 *
 * Taking an unrecognised flag for the name filter is how a mistyped `--self-check` turns into a run
 * that selects nothing, prints the no-checks sentinel and exits 0. The single-dash form is the more
 * likely typo of the two and was the one that slipped through a `--` prefix test.
 */
fun assertRejectsAnUnknownFlag(tool: NativeTool) {
    for (argument in listOf("--no-such-flag", "-selfcheck")) {
        val run = runNativeHarness(tool, listOf(argument))

        assertEquals(2, run.exitCode, "$argument must be a usage error\n${run.describe()}")
        assertTrue(
            run.checks.isEmpty(),
            "a usage error must run no checks at all\n${run.describe()}",
        )
    }
}

private const val READ_BUFFER_BYTES = 4096

@OptIn(ExperimentalForeignApi::class)
fun readEnvironment(key: String): String? = getenv(key)?.toKString()

/** An absolute form of [path], built without touching the file system so it survives a miss. */
fun absolutePath(path: String): String =
    if (path.startsWith("/")) path else "${currentDirectory()}/$path"

@OptIn(ExperimentalForeignApi::class)
fun currentDirectory(): String = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    getcwd(buffer, PATH_MAX.convert())?.toKString() ?: "."
}

@OptIn(ExperimentalForeignApi::class)
private fun systemError(): String = strerror(errno)?.toKString() ?: "error $errno"

/** Single quotes survive spaces and every shell metacharacter a path can carry. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
