import dev.yoda.harmon.config.ConfigLoader
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val EXAMPLE_CONFIG = "config/harmon.conf.example"

private const val EXAMPLE_CONFIG_KEY = "HARMON_EXAMPLE_CONFIG"

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

private fun String.keyOrNull(): String? = trim()
    .takeUnless { it.isEmpty() || it.startsWith("#") }
    ?.substringBefore('=', missingDelimiterValue = "")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
