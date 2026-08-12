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


data class NativeCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

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

fun harnessExitCode(status: Int): Int? =
    if (status and 0x7f == 0) (status shr 8) and 0xff else null

fun harnessSignal(status: Int): Int? {
    val termination = status and 0x7f
    return if (termination == 0 || termination == 0x7f) null else termination
}

class NativeTool(
    val label: String,
    val environmentKey: String,
    relativePath: String,
    override: String? = readEnvironment(environmentKey),
) {
    val path: String = absolutePath(override?.takeIf { it.isNotBlank() } ?: relativePath)

    fun location(): String =
        "resolved to $path (working directory ${currentDirectory()}, " +
            "override with $environmentKey)"
}

fun cTestHarness(): NativeTool = NativeTool(
    label = "the C harness",
    environmentKey = "HARMON_NATIVE_TEST_SCRIPT",
    relativePath = "scripts/test-native.sh",
)

fun selftestHarness(): NativeTool = NativeTool(
    label = "the selftest binary",
    environmentKey = "HARMON_SELFTEST_BIN",
    relativePath = "build/tasks/_selftest_linkMacosArm64Debug/selftest.kexe",
)

data class HarnessSource(val path: String, val modifiedAt: Double)

@OptIn(ExperimentalForeignApi::class)
fun modificationTime(path: String): Double? =
    (
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?.get(NSFileModificationDate) as? NSDate
        )?.timeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
private fun isDirectory(path: String): Boolean =
    NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?.get(NSFileType) == NSFileTypeDirectory

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

class NativeHarnessRun(
    val tool: NativeTool,
    val command: String,
    val lines: List<String>,
    val exitCode: Int?,
    val signal: Int?,
) {
    val checks: List<NativeCheck> = lines.mapNotNull(::parseNativeCheck)

    val noise: List<String> = lines.filter { it.isNotBlank() && parseNativeCheck(it) == null }

    fun describe(): String {
        val ending = signal?.let { "killed by signal $it" } ?: "exited with code $exitCode"
        return "$command $ending; ${tool.location()}" +
            if (noise.isEmpty()) "" else noise.joinToString("\n", prefix = "\noutput:\n")
    }
}

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

fun absolutePath(path: String): String =
    if (path.startsWith("/")) path else "${currentDirectory()}/$path"

@OptIn(ExperimentalForeignApi::class)
fun currentDirectory(): String = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    getcwd(buffer, PATH_MAX.convert())?.toKString() ?: "."
}

@OptIn(ExperimentalForeignApi::class)
private fun systemError(): String = strerror(errno)?.toKString() ?: "error $errno"

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
