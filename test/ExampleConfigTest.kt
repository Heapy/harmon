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
 * The other direction is the one the loader cannot fail on at all. A key the example forgets parses
 * perfectly, so completeness is asserted against `ConfigLoader.configurableKeys` — the set the
 * loader itself accepts by — rather than key by key: a rule added to the agent and not to the
 * example is a documented setting no shipped file names, and this is where that shows up.
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
    fun theShippedExampleParsesAndNamesEveryConfigurableKey() {
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
        assertEquals(
            emptySet(),
            ConfigLoader.configurableKeys - lines.mapNotNullTo(mutableSetOf()) { it.keyOrNull() },
            "the example has to carry every key documented as configurable",
        )
        assertTrue(config.orphanAlerts, "the example ships the rule on, as the README says it does")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun exampleConfigLines(path: String): List<String>? =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.lines()

/**
 * The key this line assigns, or null for the comments and blanks the loader skips.
 *
 * Read here rather than through the parsed config, which cannot answer the question: every value
 * has a default, so a key the example never names comes back from `parse` looking exactly like one
 * it names and leaves empty.
 */
private fun String.keyOrNull(): String? = trim()
    .takeUnless { it.isEmpty() || it.startsWith("#") }
    ?.substringBefore('=', missingDelimiterValue = "")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
