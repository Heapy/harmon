import dev.yoda.harmon.setup.InstallResourceOrigin
import dev.yoda.harmon.setup.ResourceLocationException
import dev.yoda.harmon.setup.ResourceLocator
import dev.yoda.harmon.setup.ResourcePathProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceLocatorTest {
    @Test
    fun resolvesAnArmHomebrewSymlinkWithoutKnowingThePrefix() {
        val realExecutable = "/opt/homebrew/Cellar/harmon/0.4.0/bin/harmon"
        val probe = installedProbe(
            root = "/opt/homebrew/Cellar/harmon/0.4.0",
            executable = realExecutable,
            aliases = mapOf("/opt/homebrew/bin/harmon" to realExecutable),
        )

        val resources = ResourceLocator.locate("/opt/homebrew/bin/harmon", probe)

        assertEquals(InstallResourceOrigin.INSTALLED, resources.origin)
        assertEquals(
            "/opt/homebrew/Cellar/harmon/0.4.0/libexec/harmon-collector",
            resources.collectorBinary,
        )
    }

    @Test
    fun resolvesAnIntelStylePrefixAndPathsContainingSpaces() {
        val root = "/Volumes/Build Disk/usr/local/Cellar/harmon/0.4.0"
        val executable = "$root/bin/harmon"

        val resources = ResourceLocator.locate(
            executable,
            installedProbe(root, executable),
        )

        assertEquals("$root/share/harmon/Harmon.icns", resources.icon)
        assertEquals("$root/share/harmon/harmon.conf.example", resources.exampleConfig)
    }

    @Test
    fun pairsDebugBuildOutputsOnlyWithTheDebugCollector() {
        val root = "/Users/tester/src/harmon"
        val executable =
            "$root/build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe"
        val probe = buildProbe(root, "Debug", executable)

        val resources = ResourceLocator.locate(executable, probe)

        assertEquals(InstallResourceOrigin.BUILD_TREE, resources.origin)
        assertEquals("Debug", resources.buildVariant)
        assertEquals(
            "$root/build/tasks/_harmon-collector_linkMacosArm64Debug/" +
                "harmon-collector.kexe",
            resources.collectorBinary,
        )
    }

    @Test
    fun resolvesAReleaseBuildFromAWorktreeWhoseNameIsNotHarmon() {
        val root = "/private/tmp/review worktree"
        val executable =
            "$root/build/tasks/_review-worktree_linkMacosArm64Release/harmon.kexe"

        val resources = ResourceLocator.locate(
            executable,
            buildProbe(root, "Release", executable),
        )

        assertEquals("Release", resources.buildVariant)
    }

    @Test
    fun rejectsAMissingCollectorInsteadOfMixingBuildVariants() {
        val root = "/Users/tester/src/harmon"
        val executable =
            "$root/build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe"
        val probe = buildProbe(root, "Release", executable)

        val failure = assertFailsWith<ResourceLocationException> {
            ResourceLocator.locate(executable, probe)
        }

        assertTrue(failure.message.orEmpty().contains("Debug"))
    }

    @Test
    fun rejectsAnUnreadableResource() {
        val root = "/prefix/Cellar/harmon/0.4.0"
        val executable = "$root/bin/harmon"
        val probe = installedProbe(root, executable).apply {
            readable.remove("$root/share/harmon/Harmon.icns")
        }

        val failure = assertFailsWith<ResourceLocationException> {
            ResourceLocator.locate(executable, probe)
        }

        assertTrue(failure.message.orEmpty().contains("Harmon.icns"))
    }
}

private class FakeResourcePathProbe(
    private val aliases: MutableMap<String, String> = mutableMapOf(),
) : ResourcePathProbe {
    val regular = mutableSetOf<String>()
    val readable = mutableSetOf<String>()
    val executable = mutableSetOf<String>()

    override fun realPath(path: String): String? {
        val resolved = aliases[path] ?: path
        return resolved.takeIf { it in regular }
    }

    override fun isRegularFile(path: String): Boolean = path in regular

    override fun isReadable(path: String): Boolean = path in readable

    override fun isExecutable(path: String): Boolean = path in executable
}

private fun installedProbe(
    root: String,
    executable: String,
    aliases: Map<String, String> = emptyMap(),
): FakeResourcePathProbe {
    val probe = FakeResourcePathProbe(aliases.toMutableMap())
    val resources = listOf(
        "$root/share/harmon/Harmon.Info.plist",
        "$root/share/harmon/Harmon.icns",
        "$root/share/harmon/harmon.conf.example",
    )
    probe.regular += executable
    probe.executable += executable
    probe.regular += "$root/libexec/harmon-collector"
    probe.executable += "$root/libexec/harmon-collector"
    probe.regular += resources
    probe.readable += resources
    return probe
}

private fun buildProbe(
    root: String,
    collectorVariant: String,
    executable: String,
): FakeResourcePathProbe {
    val probe = FakeResourcePathProbe()
    val projectMarker = "$root/project.yaml"
    val collector = "$root/build/tasks/" +
        "_harmon-collector_linkMacosArm64$collectorVariant/harmon-collector.kexe"
    val resources = listOf(
        "$root/launchd/Harmon.Info.plist",
        "$root/launchd/Harmon.icns",
        "$root/config/harmon.conf.example",
    )
    probe.regular += executable
    probe.executable += executable
    probe.regular += projectMarker
    probe.readable += projectMarker
    probe.regular += collector
    probe.executable += collector
    probe.regular += resources
    probe.readable += resources
    return probe
}
