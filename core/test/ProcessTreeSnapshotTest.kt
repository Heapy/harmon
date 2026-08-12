import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.ProcessTreeNode
import dev.yoda.harmon.model.ProcessTreeSnapshotBuilder
import dev.yoda.harmon.model.ProcessTreeSnapshotJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessTreeSnapshotTest {
    @Test
    fun aggregatesFirefoxDescendantsIntoCpuAndMemoryTotals() {
        val gibibyte = 1_073_741_824uL
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                processes = listOf(
                    processUsage(
                        pid = 100,
                        parentPid = 1,
                        name = "firefox",
                        cpuPercent = 10.0,
                        footprint = gibibyte,
                    ),
                    processUsage(
                        pid = 101,
                        parentPid = 100,
                        name = "firefox content",
                        cpuPercent = 20.0,
                        footprint = 4uL * gibibyte,
                    ),
                    processUsage(
                        pid = 102,
                        parentPid = 100,
                        name = "firefox gpu",
                        cpuPercent = 30.0,
                        footprint = 5uL * gibibyte,
                    ),
                ),
            ),
        )

        val firefox = snapshot.roots.single()

        assertEquals(gibibyte.toString(), firefox.memorySelfBytes)
        assertEquals((10uL * gibibyte).toString(), firefox.memoryTotalBytes)
        assertEquals(10.0, firefox.cpuSelfPercent)
        assertEquals(60.0, firefox.cpuTotalPercent)
        assertTrue(firefox.key.contains(":100:"))
        assertEquals("100", firefox.startedAt)

        val json = Json.parseToJsonElement(ProcessTreeSnapshotJson.encode(snapshot)).jsonObject
        val root = json.getValue("roots").jsonArray.single().jsonObject
        val memoryTotal = root.getValue("memoryTotalBytes").jsonPrimitive
        assertEquals(
            (10uL * gibibyte).toString(),
            memoryTotal.content,
        )
        assertTrue(memoryTotal.isString)
        assertTrue(root.getValue("startedAt").jsonPrimitive.isString)
    }

    @Test
    fun sortsEverySiblingSetBySubtreeMemory() {
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                processes = listOf(
                    processUsage(pid = 10, parentPid = 1, footprint = 1u),
                    processUsage(pid = 11, parentPid = 10, footprint = 10u),
                    processUsage(pid = 12, parentPid = 10, footprint = 30u),
                    processUsage(pid = 13, parentPid = 11, footprint = 40u),
                    processUsage(pid = 20, parentPid = 1, footprint = 50u),
                    processUsage(pid = 21, parentPid = 1, footprint = 50u),
                ),
            ),
        )

        assertEquals(listOf(10, 20, 21), snapshot.roots.map { it.pid })
        assertEquals(listOf(11, 12), snapshot.roots.first().children.map { it.pid })
    }

    @Test
    fun makesMissingAndInvalidParentsRoots() {
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                processes = listOf(
                    processUsage(pid = 10, parentPid = 999),
                    processUsage(pid = 20, parentPid = 0),
                ),
            ),
        )

        assertEquals(listOf(10, 20), snapshot.roots.map { it.pid })
        assertEquals(999, snapshot.roots.first().parentPid)
        assertNull(snapshot.roots.last().parentPid)
    }

    @Test
    fun breaksCyclesWithoutDroppingProcesses() {
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                processes = listOf(
                    processUsage(pid = 30, parentPid = 31, footprint = 1u),
                    processUsage(pid = 31, parentPid = 32, footprint = 2u),
                    processUsage(pid = 32, parentPid = 30, footprint = 3u),
                ),
            ),
        )

        val nodes = snapshot.roots.flatMap(ProcessTreeNode::flatten)

        assertEquals(listOf(30, 32, 31), nodes.map { it.pid })
        assertEquals("6", snapshot.roots.single().memoryTotalBytes)
        assertEquals(nodes.size, nodes.map { it.pid }.distinct().size)
    }

    @Test
    fun addsIssuePlaceholdersAndLetsFullSamplesWinDuplicates() {
        val measured = listOf(
            processUsage(pid = 100, parentPid = 1, name = "measured", footprint = 8u),
            processUsage(
                pid = 201,
                parentPid = 200,
                name = "readable child",
                cpuPercent = 7.0,
                footprint = 4u,
            ),
        )
        val issues = listOf(
            issue(pid = 100, parentPid = null, name = "stale issue"),
            issue(pid = 200, parentPid = null, name = null),
        )
        val usage = systemUsage(measured).copy(
            totalProcessCount = 3,
            inaccessibleProcessCount = 1,
            processIssues = issues,
        )

        val snapshot = ProcessTreeSnapshotBuilder.build(usage)
        val measuredNode = snapshot.roots.single { it.pid == 100 }
        val unavailableNode = snapshot.roots.single { it.pid == 200 }

        assertTrue(measuredNode.measured)
        assertNull(measuredNode.issueReason)
        assertFalse(unavailableNode.measured)
        assertEquals("PID 200", unavailableNode.name)
        assertEquals(ProcessCollectionIssueReason.PERMISSION_DENIED, unavailableNode.issueReason)
        assertEquals(listOf(201), unavailableNode.children.map { it.pid })
        assertEquals("4", unavailableNode.memoryTotalBytes)
        assertEquals(7.0, unavailableNode.cpuTotalPercent)
        assertEquals(3, snapshot.displayedProcessCount)
        assertEquals(2, snapshot.measuredProcessCount)
        assertEquals(3, snapshot.totalProcessCount)
        assertEquals(1, snapshot.inaccessibleProcessCount)
    }

    @Test
    fun marksOnlyBranchesContainingUnavailableDescendantsAsPartial() {
        val usage = systemUsage(
            listOf(
                processUsage(pid = 10, parentPid = 1),
                processUsage(pid = 11, parentPid = 10),
                processUsage(pid = 20, parentPid = 1),
                processUsage(pid = 21, parentPid = 20),
            ),
        ).copy(
            processIssues = listOf(issue(pid = 12, parentPid = 11, name = "hidden child")),
        )

        val snapshot = ProcessTreeSnapshotBuilder.build(usage)
        val partialRoot = snapshot.roots.single { it.pid == 10 }
        val partialChild = partialRoot.children.single()
        val unavailable = partialChild.children.single()
        val completeRoot = snapshot.roots.single { it.pid == 20 }

        assertEquals(1, partialRoot.unavailableProcessCount)
        assertTrue(partialRoot.totalsPartial)
        assertEquals(1, partialChild.unavailableProcessCount)
        assertTrue(partialChild.totalsPartial)
        assertEquals(1, unavailable.unavailableProcessCount)
        assertTrue(unavailable.totalsPartial)
        assertEquals(0, completeRoot.unavailableProcessCount)
        assertFalse(completeRoot.totalsPartial)
        assertFalse(completeRoot.children.single().totalsPartial)

        val json = Json.parseToJsonElement(ProcessTreeSnapshotJson.encode(snapshot)).jsonObject
        val encodedPartialRoot = json.getValue("roots").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("pid").jsonPrimitive.content == "10" }
        assertEquals("1", encodedPartialRoot.getValue("unavailableProcessCount").jsonPrimitive.content)
        assertEquals("true", encodedPartialRoot.getValue("totalsPartial").jsonPrimitive.content)
    }

    @Test
    fun saturatesOverflowAndNormalizesInvalidCpuValues() {
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                processes = listOf(
                    processUsage(
                        pid = 10,
                        parentPid = 1,
                        cpuPercent = Double.MAX_VALUE,
                        footprint = ULong.MAX_VALUE,
                    ),
                    processUsage(
                        pid = 11,
                        parentPid = 10,
                        cpuPercent = Double.MAX_VALUE,
                        footprint = 1u,
                    ),
                    processUsage(
                        pid = 20,
                        parentPid = 1,
                        cpuPercent = Double.NaN,
                        footprint = 0u,
                    ),
                ),
            ),
        )

        val saturated = snapshot.roots.single { it.pid == 10 }
        val normalized = snapshot.roots.single { it.pid == 20 }

        assertEquals(ULong.MAX_VALUE.toString(), saturated.memoryTotalBytes)
        assertEquals(Double.MAX_VALUE, saturated.cpuTotalPercent)
        assertTrue(saturated.cpuTotalPercent.isFinite())
        assertEquals(0.0, normalized.cpuSelfPercent)
        assertEquals(0.0, normalized.cpuTotalPercent)
    }

    private fun issue(pid: Int, parentPid: Int?, name: String?): ProcessCollectionIssue =
        ProcessCollectionIssue(
            pid = pid,
            parentPid = parentPid,
            uid = 501u,
            name = name,
            executablePath = null,
            reason = ProcessCollectionIssueReason.PERMISSION_DENIED,
            errorCode = 1,
        )
}

private fun ProcessTreeNode.flatten(): List<ProcessTreeNode> =
    listOf(this) + children.flatMap(ProcessTreeNode::flatten)
