package dev.yoda.harmon.setup

data class InstallResources(
    val agentBinary: String,
    val collectorBinary: String,
    val infoPlist: String,
    val icon: String,
    val exampleConfig: String,
    val origin: InstallResourceOrigin,
    val buildVariant: String? = null,
)

enum class InstallResourceOrigin {
    INSTALLED,
    BUILD_TREE,
}

class ResourceLocationException(message: String) : IllegalStateException(message)

interface ResourcePathProbe {
    fun realPath(path: String): String?

    fun isRegularFile(path: String): Boolean

    fun isReadable(path: String): Boolean

    fun isExecutable(path: String): Boolean
}

object ResourceLocator {
    fun locate(
        executablePath: String,
        probe: ResourcePathProbe,
    ): InstallResources {
        val realExecutable = probe.realPath(executablePath)
            ?: throw ResourceLocationException(
                "Unable to resolve the running executable path '$executablePath'",
            )
        requireRegularExecutable("harmon executable", realExecutable, probe)

        installedCandidate(realExecutable)?.let { candidate ->
            return validate(candidate, probe)
        }
        buildTreeCandidate(realExecutable, probe)?.let { candidate ->
            return validate(candidate, probe)
        }

        throw ResourceLocationException(
            "Cannot locate Harmon install resources relative to '$realExecutable'. " +
                "Expected bin/harmon beside libexec and share/harmon, or a " +
                "build/tasks/_*_linkMacosArm64{Debug,Release}/harmon.kexe output.",
        )
    }

    private fun installedCandidate(executable: String): InstallResources? {
        if (fileName(executable) != "harmon" || fileName(parentPath(executable)) != "bin") {
            return null
        }
        val root = parentPath(parentPath(executable))
        return InstallResources(
            agentBinary = executable,
            collectorBinary = "$root/libexec/harmon-collector",
            infoPlist = "$root/share/harmon/Harmon.Info.plist",
            icon = "$root/share/harmon/Harmon.icns",
            exampleConfig = "$root/share/harmon/harmon.conf.example",
            origin = InstallResourceOrigin.INSTALLED,
        )
    }

    private fun buildTreeCandidate(
        executable: String,
        probe: ResourcePathProbe,
    ): InstallResources? {
        val marker = "/build/tasks/"
        val markerIndex = executable.lastIndexOf(marker)
        if (markerIndex <= 0 || fileName(executable) != "harmon.kexe") {
            return null
        }
        val projectRoot = executable.substring(0, markerIndex)
        val taskDirectory = parentPath(executable)
        val taskName = fileName(taskDirectory)
        val match = BUILD_TASK_PATTERN.matchEntire(taskName) ?: return null
        val variant = match.groupValues[1]
        val projectMarker = "$projectRoot/project.yaml"
        val realProjectMarker = probe.realPath(projectMarker) ?: return null
        if (!probe.isRegularFile(realProjectMarker) || !probe.isReadable(realProjectMarker)) {
            return null
        }

        return InstallResources(
            agentBinary = executable,
            collectorBinary = "$projectRoot/build/tasks/" +
                "_harmon-collector_linkMacosArm64$variant/harmon-collector.kexe",
            infoPlist = "$projectRoot/launchd/Harmon.Info.plist",
            icon = "$projectRoot/launchd/Harmon.icns",
            exampleConfig = "$projectRoot/config/harmon.conf.example",
            origin = InstallResourceOrigin.BUILD_TREE,
            buildVariant = variant,
        )
    }

    private fun validate(
        candidate: InstallResources,
        probe: ResourcePathProbe,
    ): InstallResources {
        val agent = canonicalRegularExecutable("harmon executable", candidate.agentBinary, probe)
        val collector = canonicalRegularExecutable(
            "harmon-collector executable",
            candidate.collectorBinary,
            probe,
        )
        val info = canonicalReadableFile("Harmon.Info.plist", candidate.infoPlist, probe)
        val icon = canonicalReadableFile("Harmon.icns", candidate.icon, probe)
        val config = canonicalReadableFile("harmon.conf.example", candidate.exampleConfig, probe)
        return candidate.copy(
            agentBinary = agent,
            collectorBinary = collector,
            infoPlist = info,
            icon = icon,
            exampleConfig = config,
        )
    }

    private fun canonicalRegularExecutable(
        description: String,
        path: String,
        probe: ResourcePathProbe,
    ): String {
        val realPath = probe.realPath(path)
            ?: throw ResourceLocationException("$description was not found at '$path'")
        requireRegularExecutable(description, realPath, probe)
        return realPath
    }

    private fun requireRegularExecutable(
        description: String,
        path: String,
        probe: ResourcePathProbe,
    ) {
        if (!probe.isRegularFile(path)) {
            throw ResourceLocationException("$description is not a regular file: '$path'")
        }
        if (!probe.isExecutable(path)) {
            throw ResourceLocationException("$description is not executable: '$path'")
        }
    }

    private fun canonicalReadableFile(
        description: String,
        path: String,
        probe: ResourcePathProbe,
    ): String {
        val realPath = probe.realPath(path)
            ?: throw ResourceLocationException("$description was not found at '$path'")
        if (!probe.isRegularFile(realPath)) {
            throw ResourceLocationException("$description is not a regular file: '$realPath'")
        }
        if (!probe.isReadable(realPath)) {
            throw ResourceLocationException("$description is not readable: '$realPath'")
        }
        return realPath
    }

    private fun parentPath(path: String): String =
        path.substringBeforeLast('/', missingDelimiterValue = "")
            .ifEmpty { "/" }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private val BUILD_TASK_PATTERN =
        Regex("""^_.+_linkMacosArm64(Debug|Release)$""")
}
