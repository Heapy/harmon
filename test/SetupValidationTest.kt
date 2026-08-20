import io.heapy.harmon.setup.MacOsVersion
import io.heapy.harmon.setup.SetupException
import io.heapy.harmon.setup.SetupValidation
import io.heapy.harmon.setup.TwoPhaseCommandValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetupValidationTest {
    @Test
    fun rejectsTheUserPhaseUnderRoot() {
        val failure = assertFailsWith<SetupException> {
            SetupValidation.validateUserPhase(0u)
        }

        assertTrue(failure.message.orEmpty().contains("login user"))
    }

    @Test
    fun requiresRootAndANonRootTargetForTheSystemPhase() {
        assertFailsWith<SetupException> {
            SetupValidation.validateSystemPhase(501u, 501u, 20u)
        }
        assertFailsWith<SetupException> {
            SetupValidation.validateSystemPhase(0u, 0u, 20u)
        }
        assertEquals(
            501u to 20u,
            SetupValidation.validateSystemPhase(0u, 501u, 20u),
        )
    }

    @Test
    fun acceptsThePinnedDeploymentTargetAndNewerPatchReleases() {
        SetupValidation.validatePlatform("arm64\n", "12.0")
        SetupValidation.validatePlatform("arm64", "26.4.1")
        assertTrue(MacOsVersion.parse("12.1") > MacOsVersion.parse("12.0.9"))
    }

    @Test
    fun rejectsAnotherArchitectureAndAnOlderMacOs() {
        assertFailsWith<SetupException> {
            SetupValidation.validatePlatform("x86_64", "26.0")
        }
        assertFailsWith<SetupException> {
            SetupValidation.validatePlatform("arm64", "11.7.10")
        }
    }

    @Test
    fun rejectsABinaryFromAnotherRelease() {
        val failure = assertFailsWith<SetupException> {
            SetupValidation.validateVersionOutput(
                description = "collector",
                output = "harmon-collector 0.3.0\n",
                expectedName = "harmon-collector",
                expectedVersion = "0.4.0",
            )
        }

        assertTrue(failure.message.orEmpty().contains("one release"))
    }

    @Test
    fun validatesBothPrivilegePhasesForEveryTwoPhaseCommand() {
        listOf("stop", "uninstall").forEach { commandName ->
            TwoPhaseCommandValidation.validateUserPhase(commandName, 501u, null)
            assertEquals(
                "Run 'harmon $commandName' as the login user; it requests sudo once.",
                assertFailsWith<SetupException> {
                    TwoPhaseCommandValidation.validateUserPhase(commandName, 0u, null)
                }.message,
            )
            assertEquals(
                "--uid is valid only with --system",
                assertFailsWith<SetupException> {
                    TwoPhaseCommandValidation.validateUserPhase(commandName, 0u, 501u)
                }.message,
            )
            assertEquals(
                501u,
                TwoPhaseCommandValidation.validateSystemPhase(commandName, 0u, 501u),
            )
            assertEquals(
                "'harmon $commandName --system' must run as root",
                assertFailsWith<SetupException> {
                    TwoPhaseCommandValidation.validateSystemPhase(commandName, 501u, null)
                }.message,
            )
            assertEquals(
                "--uid is required with --system",
                assertFailsWith<SetupException> {
                    TwoPhaseCommandValidation.validateSystemPhase(commandName, 0u, null)
                }.message,
            )
            assertEquals(
                "--uid must identify a non-root login user",
                assertFailsWith<SetupException> {
                    TwoPhaseCommandValidation.validateSystemPhase(commandName, 0u, 0u)
                }.message,
            )
        }
    }
}
