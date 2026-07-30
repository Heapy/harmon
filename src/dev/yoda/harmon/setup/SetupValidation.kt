package dev.yoda.harmon.setup

import dev.yoda.harmon.BuildInfo

class SetupException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class MacOsVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0,
) : Comparable<MacOsVersion> {
    override fun compareTo(other: MacOsVersion): Int =
        compareValuesBy(this, other, MacOsVersion::major, MacOsVersion::minor, MacOsVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): MacOsVersion {
            val components = value.trim().split('.')
            if (components.size !in 1..3) {
                throw SetupException("Invalid macOS version '$value'")
            }
            val numbers = components.map { component ->
                component.toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw SetupException("Invalid macOS version '$value'")
            }
            return MacOsVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}

data class SetupRequest(
    val system: Boolean,
    val userId: UInt? = null,
    val groupId: UInt? = null,
)

data class ValidatedInstallResources(
    val executablePath: String,
    val resources: InstallResources,
)

object SetupValidation {
    fun validateUserPhase(effectiveUserId: UInt) {
        if (effectiveUserId == 0u) {
            throw SetupException(
                "Run 'harmon setup' as the login user; it requests sudo once for system files.",
            )
        }
    }

    fun validateSystemPhase(
        effectiveUserId: UInt,
        requestedUserId: UInt?,
        requestedGroupId: UInt?,
    ): Pair<UInt, UInt> {
        if (effectiveUserId != 0u) {
            throw SetupException("'harmon setup --system' must run as root")
        }
        val userId = requestedUserId
            ?: throw SetupException("--uid is required with --system")
        val groupId = requestedGroupId
            ?: throw SetupException("--gid is required with --system")
        if (userId == 0u) {
            throw SetupException("--uid must identify a non-root login user")
        }
        return userId to groupId
    }

    fun validatePlatform(
        machine: String,
        currentMacOs: String,
        minimumMacOs: String = BuildInfo.MINIMUM_MACOS,
    ) {
        if (machine.trim() != "arm64") {
            throw SetupException(
                "Harmon supports Apple Silicon (arm64); this machine reports '${machine.trim()}'",
            )
        }
        val current = MacOsVersion.parse(currentMacOs)
        val minimum = MacOsVersion.parse(minimumMacOs)
        if (current < minimum) {
            throw SetupException(
                "Harmon requires macOS $minimumMacOs or newer; this machine runs $currentMacOs",
            )
        }
    }

    fun validateVersionOutput(
        description: String,
        output: String,
        expectedName: String,
        expectedVersion: String = BuildInfo.VERSION,
    ) {
        val expected = "$expectedName $expectedVersion"
        val actual = output.trim()
        if (actual != expected) {
            throw SetupException(
                "$description reports '$actual'; expected '$expected'. " +
                    "The harmon and harmon-collector binaries must come from one release.",
            )
        }
    }
}

class SetupPrerequisiteChecker(
    private val commandRunner: CommandRunner,
    private val resourcePathProbe: ResourcePathProbe = PosixResourcePathProbe,
    private val executablePath: () -> String = ExecutablePath::current,
) {
    fun check(): ValidatedInstallResources {
        val self = executablePath()
        val resources = ResourceLocator.locate(self, resourcePathProbe)
        val machine = commandRunner.requireSuccess(
            listOf("/usr/bin/uname", "-m"),
        ).output
        val macOs = commandRunner.requireSuccess(
            listOf("/usr/bin/sw_vers", "-productVersion"),
        ).output
        SetupValidation.validatePlatform(machine, macOs)

        val agentVersion = commandRunner.requireSuccess(
            listOf(resources.agentBinary, "--version"),
        ).output
        SetupValidation.validateVersionOutput(
            description = "harmon executable",
            output = agentVersion,
            expectedName = "harmon",
        )
        val collectorVersion = commandRunner.requireSuccess(
            listOf(resources.collectorBinary, "--version"),
        ).output
        SetupValidation.validateVersionOutput(
            description = "harmon-collector executable",
            output = collectorVersion,
            expectedName = "harmon-collector",
        )
        return ValidatedInstallResources(self, resources)
    }
}
