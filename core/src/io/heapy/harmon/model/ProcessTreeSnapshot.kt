package io.heapy.harmon.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProcessTreeSnapshot(
    val capturedAt: String,
    val totalProcessCount: Int,
    val displayedProcessCount: Int,
    val measuredProcessCount: Int,
    val inaccessibleProcessCount: Int,
    val roots: List<ProcessTreeNode>,
)

@Serializable
data class ProcessMetricValue(
    val self: String?,
    val total: String?,
    val selfAvailable: Boolean,
    val totalAvailable: Boolean,
    val totalPartial: Boolean,
)

@Serializable
data class ProcessTreeMetrics(
    val cpuPercent: ProcessMetricValue,
    val userCpuPercent: ProcessMetricValue,
    val systemCpuPercent: ProcessMetricValue,
    val physicalFootprintBytes: ProcessMetricValue,
    val residentBytes: ProcessMetricValue,
    val wiredBytes: ProcessMetricValue,
    val compressedOrPagedOutBytes: ProcessMetricValue,
    val virtualMemoryRegionCount: ProcessMetricValue,
    val lifetimeMaxPhysicalFootprintBytes: ProcessMetricValue,
    val diskReadBytesPerSecond: ProcessMetricValue,
    val diskWriteBytesPerSecond: ProcessMetricValue,
    val logicalWriteBytesPerSecond: ProcessMetricValue,
    val pageInsPerSecond: ProcessMetricValue,
    val wakeupsPerSecond: ProcessMetricValue,
    val faultsPerSecond: ProcessMetricValue,
    val copyOnWriteFaultsPerSecond: ProcessMetricValue,
    val systemCallsPerSecond: ProcessMetricValue,
    val contextSwitchesPerSecond: ProcessMetricValue,
    val threadCount: ProcessMetricValue,
    val runningThreadCount: ProcessMetricValue,
    val instructionsPerSecond: ProcessMetricValue,
    val cyclesPerSecond: ProcessMetricValue,
    val energyWatts: ProcessMetricValue,
    val billedEnergyPerSecond: ProcessMetricValue,
    val batteryImpactScore: ProcessMetricValue,
)

@Serializable
data class ProcessTreeNode(
    val key: String,
    val pid: Int,
    val startedAt: String?,
    val parentPid: Int?,
    val uid: UInt?,
    val name: String,
    val executablePath: String?,
    val measured: Boolean,
    val issueReason: ProcessCollectionIssueReason?,
    val errorCode: Int?,
    val metrics: ProcessTreeMetrics,
    val unavailableProcessCount: Int,
    val children: List<ProcessTreeNode>,
)

object ProcessTreeSnapshotJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(snapshot: ProcessTreeSnapshot): String = json.encodeToString(snapshot)
}

object ProcessTreeSnapshotBuilder {
    fun build(report: MonitoringReport): ProcessTreeSnapshot = build(report.usage)

    fun build(usage: SystemUsage): ProcessTreeSnapshot {
        val drafts = buildDrafts(usage)
        val parentByPid = resolveParents(drafts)
        breakCycles(parentByPid)

        val childrenByPid = drafts.keys.associateWith { mutableListOf<Int>() }
        for ((pid, parentPid) in parentByPid) {
            parentPid?.let { childrenByPid.getValue(it).add(pid) }
        }
        val roots = drafts.keys
            .filter { parentByPid[it] == null }
            .map { buildNode(it, drafts, childrenByPid) }
            .sortedWith(nodeOrder)
            .map(BuiltNode::node)

        return ProcessTreeSnapshot(
            capturedAt = usage.capturedAt.toString(),
            totalProcessCount = usage.totalProcessCount,
            displayedProcessCount = drafts.size,
            measuredProcessCount = usage.processes.distinctBy { it.identity.pid }.size,
            inaccessibleProcessCount = usage.inaccessibleProcessCount,
            roots = roots,
        )
    }

    private fun buildDrafts(usage: SystemUsage): Map<Int, NodeDraft> = buildMap {
        for (process in usage.processes) {
            put(process.identity.pid, NodeDraft.measured(process))
        }
        for (issue in usage.processIssues) {
            if (issue.pid !in this) put(issue.pid, NodeDraft.issue(issue))
        }
    }

    private fun resolveParents(drafts: Map<Int, NodeDraft>): MutableMap<Int, Int?> =
        drafts.mapValuesTo(mutableMapOf()) { (pid, draft) ->
            draft.parentPid?.takeIf { it > 0 && it != pid && it in drafts }
        }

    private fun breakCycles(parentByPid: MutableMap<Int, Int?>) {
        val resolved = mutableSetOf<Int>()
        for (startPid in parentByPid.keys.sorted()) {
            if (startPid in resolved) continue

            val path = mutableListOf<Int>()
            val pathIndexByPid = mutableMapOf<Int, Int>()
            var currentPid: Int? = startPid
            while (currentPid != null && currentPid !in resolved) {
                val cycleStart = pathIndexByPid[currentPid]
                if (cycleStart != null) {
                    val rootPid = path.subList(cycleStart, path.size).min()
                    parentByPid[rootPid] = null
                    break
                }
                pathIndexByPid[currentPid] = path.size
                path += currentPid
                currentPid = parentByPid[currentPid]
            }
            resolved += path
        }
    }

    private fun buildNode(
        pid: Int,
        drafts: Map<Int, NodeDraft>,
        childrenByPid: Map<Int, List<Int>>,
    ): BuiltNode {
        val draft = drafts.getValue(pid)
        val children = childrenByPid.getValue(pid)
            .map { buildNode(it, drafts, childrenByPid) }
            .sortedWith(nodeOrder)
        val totals = MetricKey.entries.associateWith { key ->
            aggregateMetric(key, draft.metrics.getValue(key), children)
        }
        val unavailableProcessCount =
            children.sumOf { it.node.unavailableProcessCount } + if (draft.measured) 0 else 1

        return BuiltNode(
            node = ProcessTreeNode(
                key = draft.key,
                pid = draft.pid,
                startedAt = draft.startedAt?.toString(),
                parentPid = draft.parentPid,
                uid = draft.uid,
                name = draft.name,
                executablePath = draft.executablePath,
                measured = draft.measured,
                issueReason = draft.issueReason,
                errorCode = draft.errorCode,
                metrics = totals.toPublicMetrics(),
                unavailableProcessCount = unavailableProcessCount,
                children = children.map(BuiltNode::node),
            ),
            totals = totals,
        )
    }

    private fun aggregateMetric(
        key: MetricKey,
        self: MetricNumber?,
        children: List<BuiltNode>,
    ): MetricTotal {
        if (key.selfOnly) {
            return MetricTotal(
                self = self,
                total = null,
                totalAvailable = false,
                totalPartial = false,
            )
        }

        var total = self
        var available = self != null
        var partial = self == null
        for (child in children) {
            val childMetric = child.totals.getValue(key)
            if (childMetric.totalAvailable) {
                total = total?.plus(childMetric.total) ?: childMetric.total
                available = true
            }
            partial = partial || childMetric.totalPartial
        }
        return MetricTotal(
            self = self,
            total = total,
            totalAvailable = available,
            totalPartial = partial,
        )
    }

    private fun Map<MetricKey, MetricTotal>.toPublicMetrics(): ProcessTreeMetrics =
        ProcessTreeMetrics(
            cpuPercent = getValue(MetricKey.CPU).publicValue(),
            userCpuPercent = getValue(MetricKey.USER_CPU).publicValue(),
            systemCpuPercent = getValue(MetricKey.SYSTEM_CPU).publicValue(),
            physicalFootprintBytes = getValue(MetricKey.PHYSICAL_FOOTPRINT).publicValue(),
            residentBytes = getValue(MetricKey.RESIDENT).publicValue(),
            wiredBytes = getValue(MetricKey.WIRED).publicValue(),
            compressedOrPagedOutBytes = getValue(MetricKey.COMPRESSED).publicValue(),
            virtualMemoryRegionCount = getValue(MetricKey.VM_REGIONS).publicValue(),
            lifetimeMaxPhysicalFootprintBytes = getValue(MetricKey.LIFETIME_PEAK).publicValue(),
            diskReadBytesPerSecond = getValue(MetricKey.DISK_READ).publicValue(),
            diskWriteBytesPerSecond = getValue(MetricKey.DISK_WRITE).publicValue(),
            logicalWriteBytesPerSecond = getValue(MetricKey.LOGICAL_WRITES).publicValue(),
            pageInsPerSecond = getValue(MetricKey.PAGE_INS).publicValue(),
            wakeupsPerSecond = getValue(MetricKey.WAKEUPS).publicValue(),
            faultsPerSecond = getValue(MetricKey.FAULTS).publicValue(),
            copyOnWriteFaultsPerSecond = getValue(MetricKey.COW_FAULTS).publicValue(),
            systemCallsPerSecond = getValue(MetricKey.SYSTEM_CALLS).publicValue(),
            contextSwitchesPerSecond = getValue(MetricKey.CONTEXT_SWITCHES).publicValue(),
            threadCount = getValue(MetricKey.THREADS).publicValue(),
            runningThreadCount = getValue(MetricKey.RUNNING_THREADS).publicValue(),
            instructionsPerSecond = getValue(MetricKey.INSTRUCTIONS).publicValue(),
            cyclesPerSecond = getValue(MetricKey.CYCLES).publicValue(),
            energyWatts = getValue(MetricKey.WATTS).publicValue(),
            billedEnergyPerSecond = getValue(MetricKey.BILLED_ENERGY).publicValue(),
            batteryImpactScore = getValue(MetricKey.BATTERY_IMPACT).publicValue(),
        )

    private fun MetricTotal.publicValue(): ProcessMetricValue = ProcessMetricValue(
        self = self?.encoded,
        total = total?.encoded,
        selfAvailable = self != null,
        totalAvailable = totalAvailable,
        totalPartial = totalPartial,
    )

    private val nodeOrder = compareByDescending<BuiltNode> {
        (it.totals.getValue(MetricKey.PHYSICAL_FOOTPRINT).total as? MetricNumber.Integer)?.value
            ?: 0u
    }.thenBy { it.node.pid }.thenBy { it.node.key }

    private data class BuiltNode(
        val node: ProcessTreeNode,
        val totals: Map<MetricKey, MetricTotal>,
    )

    private data class MetricTotal(
        val self: MetricNumber?,
        val total: MetricNumber?,
        val totalAvailable: Boolean,
        val totalPartial: Boolean,
    )

    private sealed interface MetricNumber {
        val encoded: String

        operator fun plus(other: MetricNumber?): MetricNumber = when {
            other == null -> this
            this is Integer && other is Integer -> Integer(value.saturatingAdd(other.value))
            this is Decimal && other is Decimal -> Decimal(value.saturatingAdd(other.value))
            else -> error("metric kinds must agree within one column")
        }

        data class Integer(val value: ULong) : MetricNumber {
            override val encoded: String
                get() = value.toString()
        }

        data class Decimal(val value: Double) : MetricNumber {
            override val encoded: String
                get() = value.finiteNonNegative().toString()
        }
    }

    private enum class MetricKey(val selfOnly: Boolean = false) {
        CPU,
        USER_CPU,
        SYSTEM_CPU,
        PHYSICAL_FOOTPRINT,
        RESIDENT,
        WIRED,
        COMPRESSED,
        VM_REGIONS,
        LIFETIME_PEAK(selfOnly = true),
        DISK_READ,
        DISK_WRITE,
        LOGICAL_WRITES,
        PAGE_INS,
        WAKEUPS,
        FAULTS,
        COW_FAULTS,
        SYSTEM_CALLS,
        CONTEXT_SWITCHES,
        THREADS,
        RUNNING_THREADS,
        INSTRUCTIONS,
        CYCLES,
        WATTS,
        BILLED_ENERGY,
        BATTERY_IMPACT,
    }

    private data class NodeDraft(
        val key: String,
        val pid: Int,
        val startedAt: ULong?,
        val parentPid: Int?,
        val uid: UInt?,
        val name: String,
        val executablePath: String?,
        val measured: Boolean,
        val issueReason: ProcessCollectionIssueReason?,
        val errorCode: Int?,
        val metrics: Map<MetricKey, MetricNumber?>,
    ) {
        companion object {
            fun measured(process: ProcessUsage): NodeDraft = NodeDraft(
                key = "process:${process.identity.pid}:${process.identity.startedAt}",
                pid = process.identity.pid,
                startedAt = process.identity.startedAt,
                parentPid = process.parentPid.takeIf { it > 0 },
                uid = process.uid,
                name = process.name,
                executablePath = process.executablePath,
                measured = true,
                issueReason = null,
                errorCode = null,
                metrics = mapOf(
                    MetricKey.CPU to process.cpuPercent.decimal(),
                    MetricKey.USER_CPU to process.userCpuPercent.decimal(),
                    MetricKey.SYSTEM_CPU to process.systemCpuPercent.decimal(),
                    MetricKey.PHYSICAL_FOOTPRINT to process.physicalFootprintBytes.integer(),
                    MetricKey.RESIDENT to process.residentBytes.integer(),
                    MetricKey.WIRED to process.wiredBytes.integer(),
                    MetricKey.COMPRESSED to process.compressedOrPagedOutBytes?.integer(),
                    MetricKey.VM_REGIONS to process.virtualMemoryRegionCount?.toULong()?.integer(),
                    MetricKey.LIFETIME_PEAK to process.lifetimeMaxPhysicalFootprintBytes.integer(),
                    MetricKey.DISK_READ to process.diskReadBytesPerSecond.decimal(),
                    MetricKey.DISK_WRITE to process.diskWriteBytesPerSecond.decimal(),
                    MetricKey.LOGICAL_WRITES to process.logicalWriteBytesPerSecond.decimal(),
                    MetricKey.PAGE_INS to process.pageInsPerSecond.decimal(),
                    MetricKey.WAKEUPS to process.wakeupsPerSecond.decimal(),
                    MetricKey.FAULTS to process.faultsPerSecond.decimal(),
                    MetricKey.COW_FAULTS to process.copyOnWriteFaultsPerSecond.decimal(),
                    MetricKey.SYSTEM_CALLS to process.systemCallsPerSecond.decimal(),
                    MetricKey.CONTEXT_SWITCHES to process.contextSwitchesPerSecond.decimal(),
                    MetricKey.THREADS to process.threadCount.coerceAtLeast(0).toULong().integer(),
                    MetricKey.RUNNING_THREADS to
                        process.runningThreadCount.coerceAtLeast(0).toULong().integer(),
                    MetricKey.INSTRUCTIONS to process.instructionsPerSecond.decimal(),
                    MetricKey.CYCLES to process.cyclesPerSecond.decimal(),
                    MetricKey.WATTS to process.energyWatts.decimal(),
                    MetricKey.BILLED_ENERGY to process.billedEnergyPerSecond.decimal(),
                    MetricKey.BATTERY_IMPACT to process.batteryImpactScore.decimal(),
                ),
            )

            fun issue(issue: ProcessCollectionIssue): NodeDraft = NodeDraft(
                key = "process:${issue.pid}:unavailable",
                pid = issue.pid,
                startedAt = null,
                parentPid = issue.parentPid?.takeIf { it > 0 },
                uid = issue.uid,
                name = issue.name ?: "PID ${issue.pid}",
                executablePath = issue.executablePath,
                measured = false,
                issueReason = issue.reason,
                errorCode = issue.errorCode,
                metrics = MetricKey.entries.associateWith { null },
            )
        }
    }

    private fun ULong.integer(): MetricNumber = MetricNumber.Integer(this)

    private fun Double.decimal(): MetricNumber = MetricNumber.Decimal(finiteNonNegative())

    private fun ULong.saturatingAdd(other: ULong): ULong =
        if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

    private fun Double.saturatingAdd(other: Double): Double {
        val sum = this + other
        return if (sum.isFinite()) sum else Double.MAX_VALUE
    }

    private fun Double.finiteNonNegative(): Double =
        takeIf { isFinite() && this >= 0.0 } ?: 0.0
}
