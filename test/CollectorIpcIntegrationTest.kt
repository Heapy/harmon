import kotlin.test.Test

private fun collectorIpcBinary(): NativeTool = NativeTool(
    label = "the debug collector binary",
    environmentKey = "HARMON_COLLECTOR_BIN",
    relativePath = "build/tasks/_harmon-collector_linkMacosArm64Debug/harmon-collector.kexe",
)

private val COLLECTOR_IPC_BINARY_SOURCES = listOf(
    "harmon-collector/src",
    "harmon-collector/module.yaml",
    "bridge-ipc/cinterop/harmon_ipc.def",
    "bridge-ipc/module.yaml",
    "bridge-probe/cinterop/harmon_probe.def",
    "bridge-probe/module.yaml",
    "core/src",
    "core/module.yaml",
    "harmon.module-template.yaml",
)

fun assertCollectorIpcBinaryIsCurrent() {
    assertHarnessIsCurrent(collectorIpcBinary(), COLLECTOR_IPC_BINARY_SOURCES)
}

class CollectorIpcIntegrationTest {
    @Test
    fun requiresACurrentRealCollectorBinary() {
        assertCollectorIpcBinaryIsCurrent()
    }
}
