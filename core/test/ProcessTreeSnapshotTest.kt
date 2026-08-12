import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.ProcessMetricValue
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
    fun aggregatesSelfAndTotalMetricsAcrossEveryColumnGroup() {
        val parent = processUsage(
            pid = 100,
            parentPid = 1,
            name = "parent",
            cpuPercent = 3.0,
            footprint = 100u,
            compressedOrPagedOutBytes = 40u,
        ).copy(
            userCpuPercent = 1.0,
            systemCpuPercent = 2.0,
            residentBytes = 80u,
            wiredBytes = 30u,
            lifetimeMaxPhysicalFootprintBytes = 1_000u,
            virtualMemoryRegionCount = 5,
            diskReadBytesPerSecond = 8.0,
            diskWriteBytesPerSecond = 9.0,
            logicalWriteBytesPerSecond = 10.0,
            pageInsPerSecond = 7.0,
            wakeupsPerSecond = 6.0,
            faultsPerSecond = 14.0,
            copyOnWriteFaultsPerSecond = 15.0,
            systemCallsPerSecond = 16.0,
            contextSwitchesPerSecond = 17.0,
            threadCount = 18,
            runningThreadCount = 2,
            instructionsPerSecond = 11.0,
            cyclesPerSecond = 12.0,
            energyWatts = 13.0,
            billedEnergyPerSecond = 20.0,
            batteryImpactScore = 21.0,
        )
        val child = processUsage(
            pid = 101,
            parentPid = 100,
            name = "child",
            cpuPercent = 4.0,
            footprint = 10u,
            compressedOrPagedOutBytes = 4u,
        ).copy(
            userCpuPercent = 3.0,
            systemCpuPercent = 1.0,
            residentBytes = 8u,
            wiredBytes = 3u,
            lifetimeMaxPhysicalFootprintBytes = 2_000u,
            virtualMemoryRegionCount = 2,
            diskReadBytesPerSecond = 1.0,
            diskWriteBytesPerSecond = 1.0,
            logicalWriteBytesPerSecond = 1.0,
            pageInsPerSecond = 1.0,
            wakeupsPerSecond = 1.0,
            faultsPerSecond = 1.0,
            copyOnWriteFaultsPerSecond = 1.0,
            systemCallsPerSecond = 1.0,
            contextSwitchesPerSecond = 1.0,
            threadCount = 3,
            runningThreadCount = 1,
            instructionsPerSecond = 1.0,
            cyclesPerSecond = 1.0,
            energyWatts = 1.0,
            billedEnergyPerSecond = 1.0,
            batteryImpactScore = 1.0,
        )

        val node = ProcessTreeSnapshotBuilder.build(systemUsage(listOf(parent, child))).roots.single()
        val metrics = node.metrics

        assertMetric(metrics.cpuPercent, "3.0", "7.0")
        assertMetric(metrics.userCpuPercent, "1.0", "4.0")
        assertMetric(metrics.systemCpuPercent, "2.0", "3.0")
        assertMetric(metrics.physicalFootprintBytes, "100", "110")
        assertMetric(metrics.residentBytes, "80", "88")
        assertMetric(metrics.wiredBytes, "30", "33")
        assertMetric(metrics.compressedOrPagedOutBytes, "40", "44")
        assertMetric(metrics.virtualMemoryRegionCount, "5", "7")
        assertMetric(metrics.diskReadBytesPerSecond, "8.0", "9.0")
        assertMetric(metrics.diskWriteBytesPerSecond, "9.0", "10.0")
        assertMetric(metrics.logicalWriteBytesPerSecond, "10.0", "11.0")
        assertMetric(metrics.pageInsPerSecond, "7.0", "8.0")
        assertMetric(metrics.wakeupsPerSecond, "6.0", "7.0")
        assertMetric(metrics.faultsPerSecond, "14.0", "15.0")
        assertMetric(metrics.copyOnWriteFaultsPerSecond, "15.0", "16.0")
        assertMetric(metrics.systemCallsPerSecond, "16.0", "17.0")
        assertMetric(metrics.contextSwitchesPerSecond, "17.0", "18.0")
        assertMetric(metrics.threadCount, "18", "21")
        assertMetric(metrics.runningThreadCount, "2", "3")
        assertMetric(metrics.instructionsPerSecond, "11.0", "12.0")
        assertMetric(metrics.cyclesPerSecond, "12.0", "13.0")
        assertMetric(metrics.energyWatts, "13.0", "14.0")
        assertMetric(metrics.billedEnergyPerSecond, "20.0", "21.0")
        assertMetric(metrics.batteryImpactScore, "21.0", "22.0")

        val lifetimePeak = metrics.lifetimeMaxPhysicalFootprintBytes
        assertEquals("1000", lifetimePeak.self)
        assertTrue(lifetimePeak.selfAvailable)
        assertNull(lifetimePeak.total)
        assertFalse(lifetimePeak.totalAvailable)
        assertFalse(lifetimePeak.totalPartial)
        assertTrue(node.key.contains(":100:"))
        assertEquals("100", node.startedAt)
    }

    @Test
    fun encodesAll64BitMetricValuesAsJsonStrings() {
        val maximum = ULong.MAX_VALUE
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                listOf(
                    processUsage(pid = 100, footprint = maximum).copy(
                        residentBytes = maximum,
                        wiredBytes = maximum,
                        lifetimeMaxPhysicalFootprintBytes = maximum,
                        threadCount = Int.MAX_VALUE,
                    ),
                ),
            ),
        )

        val json = Json.parseToJsonElement(ProcessTreeSnapshotJson.encode(snapshot)).jsonObject
        val root = json.getValue("roots").jsonArray.single().jsonObject
        val metrics = root.getValue("metrics").jsonObject
        val footprint = metrics.getValue("physicalFootprintBytes").jsonObject

        assertEquals(maximum.toString(), footprint.getValue("self").jsonPrimitive.content)
        assertTrue(footprint.getValue("self").jsonPrimitive.isString)
        assertEquals(maximum.toString(), footprint.getValue("total").jsonPrimitive.content)
        assertTrue(root.getValue("startedAt").jsonPrimitive.isString)
    }

    @Test
    fun sortsEverySiblingSetBySubtreePhysicalFootprint() {
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
        assertEquals("6", snapshot.roots.single().metrics.physicalFootprintBytes.total)
        assertEquals(nodes.size, nodes.map { it.pid }.distinct().size)
    }

    @Test
    fun issuePlaceholderKeepsKnownChildTotalsSortableAndMarksThemPartial() {
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
        val usage = systemUsage(measured).copy(
            totalProcessCount = 3,
            inaccessibleProcessCount = 1,
            processIssues = listOf(
                issue(pid = 100, parentPid = null, name = "stale issue"),
                issue(pid = 200, parentPid = null, name = null),
            ),
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
        assertMetric(
            unavailableNode.metrics.physicalFootprintBytes,
            self = null,
            total = "4",
            selfAvailable = false,
            totalAvailable = true,
            totalPartial = true,
        )
        assertMetric(
            unavailableNode.metrics.cpuPercent,
            self = null,
            total = "7.0",
            selfAvailable = false,
            totalAvailable = true,
            totalPartial = true,
        )
        assertEquals(3, snapshot.displayedProcessCount)
        assertEquals(2, snapshot.measuredProcessCount)
        assertEquals(3, snapshot.totalProcessCount)
        assertEquals(1, snapshot.inaccessibleProcessCount)
    }

    @Test
    fun missingAttributionOnlyMakesAttributionColumnsPartial() {
        val snapshot = ProcessTreeSnapshotBuilder.build(
            systemUsage(
                listOf(
                    processUsage(pid = 10, footprint = 10u, compressedOrPagedOutBytes = 3u),
                    processUsage(
                        pid = 11,
                        parentPid = 10,
                        footprint = 5u,
                        compressedOrPagedOutBytes = null,
                    ),
                ),
            ),
        )
        val root = snapshot.roots.single()
        val child = root.children.single()

        assertMetric(root.metrics.compressedOrPagedOutBytes, "3", "3", totalPartial = true)
        assertMetric(root.metrics.virtualMemoryRegionCount, "12", "12", totalPartial = true)
        assertMetric(root.metrics.physicalFootprintBytes, "10", "15")
        assertMetric(
            child.metrics.compressedOrPagedOutBytes,
            self = null,
            total = null,
            selfAvailable = false,
            totalAvailable = false,
            totalPartial = true,
        )
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
        assertTrue(partialRoot.metrics.cpuPercent.totalPartial)
        assertEquals(1, partialChild.unavailableProcessCount)
        assertTrue(partialChild.metrics.cpuPercent.totalPartial)
        assertEquals(1, unavailable.unavailableProcessCount)
        assertTrue(unavailable.metrics.cpuPercent.totalPartial)
        assertEquals(0, completeRoot.unavailableProcessCount)
        assertFalse(completeRoot.metrics.cpuPercent.totalPartial)
        assertFalse(completeRoot.children.single().metrics.cpuPercent.totalPartial)

        val json = Json.parseToJsonElement(ProcessTreeSnapshotJson.encode(snapshot)).jsonObject
        val encodedPartialRoot = json.getValue("roots").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("pid").jsonPrimitive.content == "10" }
        assertEquals("1", encodedPartialRoot.getValue("unavailableProcessCount").jsonPrimitive.content)
        assertEquals(
            "true",
            encodedPartialRoot.getValue("metrics").jsonObject
                .getValue("cpuPercent").jsonObject
                .getValue("totalPartial").jsonPrimitive.content,
        )
    }

    @Test
    fun saturatesOverflowAndNormalizesInvalidDecimalValues() {
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

        assertEquals(ULong.MAX_VALUE.toString(), saturated.metrics.physicalFootprintBytes.total)
        assertEquals(Double.MAX_VALUE.toString(), saturated.metrics.cpuPercent.total)
        assertEquals("0.0", normalized.metrics.cpuPercent.self)
        assertEquals("0.0", normalized.metrics.cpuPercent.total)
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

private fun assertMetric(
    metric: ProcessMetricValue,
    self: String?,
    total: String?,
    selfAvailable: Boolean = true,
    totalAvailable: Boolean = true,
    totalPartial: Boolean = false,
) {
    assertEquals(self, metric.self)
    assertEquals(total, metric.total)
    assertEquals(selfAvailable, metric.selfAvailable)
    assertEquals(totalAvailable, metric.totalAvailable)
    assertEquals(totalPartial, metric.totalPartial)
}

private fun ProcessTreeNode.flatten(): List<ProcessTreeNode> =
    listOf(this) + children.flatMap(ProcessTreeNode::flatten)
