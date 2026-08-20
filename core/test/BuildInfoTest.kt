import io.heapy.harmon.BuildInfo
import io.heapy.harmon.ipc.CollectorProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun publishesTheProtocolVersionUsedByTheSharedEnvelope() {
        assertEquals(CollectorProtocol.VERSION, BuildInfo.COLLECTOR_PROTOCOL_VERSION)
    }

    @Test
    fun pinsTheDeploymentTargetObservedInTheNativeExecutable() {
        assertEquals("12.0", BuildInfo.MINIMUM_MACOS)
    }
}
