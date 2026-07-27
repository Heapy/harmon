package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.ProcessUsage

/**
 * Resolves a readable process to its outermost macOS application bundle.
 *
 * A process whose executable is outside an `.app` bundle inherits the bundle
 * of its nearest readable ancestor. Processes without an application bundle
 * remain independent groups.
 */
class ApplicationGrouper {
    fun group(processes: List<ProcessUsage>): List<ApplicationUsage> {
        val processesByPid = processes.associateBy { it.identity.pid }
        val bundleByPid = mutableMapOf<Int, String?>()

        fun resolveBundlePath(pid: Int, visiting: MutableSet<Int>): String? {
            if (pid in bundleByPid) {
                return bundleByPid[pid]
            }
            val process = processesByPid[pid] ?: return null
            val directBundle = process.executablePath?.outermostApplicationBundle()
            if (directBundle != null) {
                bundleByPid[pid] = directBundle
                return directBundle
            }
            if (!visiting.add(pid)) {
                return null
            }
            val inheritedBundle = resolveBundlePath(process.parentPid, visiting)
            visiting.remove(pid)
            bundleByPid[pid] = inheritedBundle
            return inheritedBundle
        }

        val assignments = processes.groupBy { process ->
            val bundlePath = resolveBundlePath(
                pid = process.identity.pid,
                visiting = mutableSetOf(),
            )
            GroupAssignment(
                id = bundlePath?.let { "bundle:${it.stableHash()}" }
                    ?: "process:${process.identity.pid}:${process.identity.startedAt}",
                bundlePath = bundlePath,
            )
        }

        return assignments
            .map { (assignment, members) -> aggregate(assignment, members) }
            .sortedWith(compareBy<ApplicationUsage> { it.name.lowercase() }.thenBy { it.id })
    }

    private fun aggregate(
        assignment: GroupAssignment,
        members: List<ProcessUsage>,
    ): ApplicationUsage {
        val memberPids = members.mapTo(mutableSetOf()) { it.identity.pid }
        val root = members
            .asSequence()
            .filter { it.parentPid !in memberPids }
            .minWithOrNull(
                compareBy<ProcessUsage> { it.identity.startedAt }
                    .thenBy { it.identity.pid },
            )
            ?: members.minBy { it.identity.pid }

        return ApplicationUsage(
            id = assignment.id,
            name = assignment.bundlePath?.applicationName() ?: root.name,
            bundlePath = assignment.bundlePath,
            rootPid = root.identity.pid,
            processIds = members.map { it.identity.pid }.sorted(),
            cpuPercent = members.sumOf { it.cpuPercent }.finiteNonNegative(),
            userCpuPercent = members.sumOf { it.userCpuPercent }.finiteNonNegative(),
            systemCpuPercent = members.sumOf { it.systemCpuPercent }.finiteNonNegative(),
            physicalFootprintBytes = members.saturatingSumOf {
                it.physicalFootprintBytes
            },
            residentBytes = members.saturatingSumOf { it.residentBytes },
            wakeupsPerSecond = members.sumOf { it.wakeupsPerSecond }.finiteNonNegative(),
            diskReadBytesPerSecond = members
                .sumOf { it.diskReadBytesPerSecond }
                .finiteNonNegative(),
            diskWriteBytesPerSecond = members
                .sumOf { it.diskWriteBytesPerSecond }
                .finiteNonNegative(),
            billedEnergyPerSecond = members
                .sumOf { it.billedEnergyPerSecond }
                .finiteNonNegative(),
            batteryImpactScore = members
                .sumOf { it.batteryImpactScore }
                .finiteNonNegative(),
        )
    }

    private fun String.outermostApplicationBundle(): String? {
        val markerIndex = lowercase().indexOf(APP_BUNDLE_MARKER)
        return takeIf { markerIndex >= 0 }?.substring(0, markerIndex + APP_EXTENSION_LENGTH)
    }

    private fun String.applicationName(): String =
        substringAfterLast('/').dropLast(APP_EXTENSION_LENGTH)

    private fun String.stableHash(): String {
        var hash = FNV_OFFSET_BASIS
        encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
        }
        return hash.toString(radix = 16).padStart(16, '0')
    }

    private fun Iterable<ProcessUsage>.saturatingSumOf(
        value: (ProcessUsage) -> ULong,
    ): ULong = fold(0uL) { total, process ->
        val next = value(process)
        if (ULong.MAX_VALUE - total < next) ULong.MAX_VALUE else total + next
    }

    private fun Double.finiteNonNegative(): Double =
        takeIf { isFinite() && this >= 0.0 } ?: 0.0

    private data class GroupAssignment(
        val id: String,
        val bundlePath: String?,
    )

    private companion object {
        const val APP_BUNDLE_MARKER = ".app/"
        const val APP_EXTENSION_LENGTH = 4
        const val FNV_OFFSET_BASIS: ULong = 14_695_981_039_346_656_037u
        const val FNV_PRIME: ULong = 1_099_511_628_211u
    }
}
