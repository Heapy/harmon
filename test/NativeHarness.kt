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
import kotlin.test.fail

/*
 * The bridge from `kotlin.test` to the external harnesses.
 *
 * KTC-5573 keeps the cinterop klib out of the test compilation, so the native bridge is exercised
 * by separate binaries — the C harness built by `scripts/test-native.sh`, and later `selftest` —
 * that speak one line-based protocol:
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

/** Everything the selftest binary is built from: its own sources and the bridge it links. */
val SELFTEST_SOURCES: List<String> = listOf(
    "selftest/src",
    "nativebridge/cinterop/harmon_native.def",
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
 * A failing check is reported as its own assertion carrying its own text, so what breaks the build
 * is `attribution.dead-pid-not-measured`, not "the native suite". The remaining conditions cover
 * the ways a harness can look green without having run: a crash, a non-zero exit with nothing
 * printed, an empty output, or a check that quietly stopped being executed.
 */
fun assertHarnessSucceeded(run: NativeHarnessRun, expectedChecks: Set<String>) {
    val failures = run.checks.filterNot { it.passed }
    val alsoFailing = failures.drop(1).joinToString(", ") { it.name }
    failures.forEach { check ->
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
