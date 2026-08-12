package dev.yoda.harmon.model

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
    val cpuSelfPercent: Double,
    val cpuTotalPercent: Double,
    val memorySelfBytes: String,
    val memoryTotalBytes: String,
    val unavailableProcessCount: Int,
    val totalsPartial: Boolean,
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
        val rootPids = drafts.keys.filter { parentByPid[it] == null }
        val roots = rootPids
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
            if (issue.pid !in this) {
                put(issue.pid, NodeDraft.issue(issue))
            }
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
        val memoryTotal = children.fold(draft.memorySelfBytes) { total, child ->
            total.saturatingAdd(child.memoryTotalBytes)
        }
        val cpuTotal = children.fold(draft.cpuSelfPercent) { total, child ->
            total.saturatingAdd(child.node.cpuTotalPercent)
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
                cpuSelfPercent = draft.cpuSelfPercent,
                cpuTotalPercent = cpuTotal,
                memorySelfBytes = draft.memorySelfBytes.toString(),
                memoryTotalBytes = memoryTotal.toString(),
                unavailableProcessCount = unavailableProcessCount,
                totalsPartial = unavailableProcessCount > 0,
                children = children.map(BuiltNode::node),
            ),
            memoryTotalBytes = memoryTotal,
        )
    }

    private val nodeOrder =
        compareByDescending<BuiltNode> { it.memoryTotalBytes }
            .thenBy { it.node.pid }
            .thenBy { it.node.key }

    private fun ULong.saturatingAdd(other: ULong): ULong =
        if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

    private fun Double.saturatingAdd(other: Double): Double {
        val sum = this + other
        return if (sum.isFinite()) sum else Double.MAX_VALUE
    }

    private fun Double.finiteNonNegative(): Double =
        takeIf { isFinite() && this >= 0.0 } ?: 0.0

    private data class BuiltNode(
        val node: ProcessTreeNode,
        val memoryTotalBytes: ULong,
    )

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
        val cpuSelfPercent: Double,
        val memorySelfBytes: ULong,
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
                cpuSelfPercent = process.cpuPercent.finiteNonNegative(),
                memorySelfBytes = process.physicalFootprintBytes,
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
                cpuSelfPercent = 0.0,
                memorySelfBytes = 0u,
            )
        }
    }
}
