import dev.yoda.harmon.config.ConfigLoader
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Where the shipped example lives, relative to the working directory the test binary runs in. */
private const val EXAMPLE_CONFIG = "config/harmon.conf.example"

/** The environment key overriding [EXAMPLE_CONFIG], for a run from somewhere other than the root. */
private const val EXAMPLE_CONFIG_KEY = "HARMON_EXAMPLE_CONFIG"

/**
 * Runs the file the installer copies to a new machine through the loader that will read it there.
 *
 * `ConfigLoader` throws on an unknown key, and the CLI turns that into an exit status of 2, so a key
 * misspelled in the example ships a configuration `harmon check-config` refuses on a machine that
 * has changed nothing. Nothing else in the suite reads this file: every other config test writes its
 * own lines, which is precisely how a typo in the shipped one stays green.
 *
 * The environment is passed empty rather than read, because `parse` lets `HARMON_WEBHOOK_URL` and
 * friends override what the file says — a developer with one exported would otherwise be testing
 * their shell rather than the example.
 *
 * The test lives in the root module rather than beside the loader in `core` because the file is
 * found by path, and this is the module whose working directory is already known to be the project
 * root: `NativeHarness` resolves `scripts/test-native.sh` against it the same way.
 */
class ExampleConfigTest {
    @Test
    fun theShippedExampleParsesWithEveryKeyItNames() {
        val path = absolutePath(
            readEnvironment(EXAMPLE_CONFIG_KEY)?.takeIf { it.isNotBlank() } ?: EXAMPLE_CONFIG,
        )
        val lines = exampleConfigLines(path)
            ?: fail(
                "cannot read the shipped example config at $path " +
                    "(working directory ${currentDirectory()}, override with $EXAMPLE_CONFIG_KEY)",
            )

        val warnings = mutableListOf<String>()
        val config = ConfigLoader.parse(
            lines = lines.asSequence(),
            environment = emptyMap(),
            warn = { warnings += it },
        )

        assertEquals(emptyList(), warnings, "the shipped example must not name a retired key")
        assertTrue(
            lines.any { it.startsWith("orphanAlerts=") },
            "the example is expected to carry every key documented as configurable",
        )
        assertTrue(config.orphanAlerts, "the example ships the rule on, as the README says it does")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun exampleConfigLines(path: String): List<String>? =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.lines()
