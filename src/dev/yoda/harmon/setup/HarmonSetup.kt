package dev.yoda.harmon.setup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.getegid
import platform.posix.geteuid
import platform.posix.getenv
import platform.posix.getgrnam
import platform.posix.getpwuid

interface AccountDirectory {
    fun homeDirectory(userId: UInt): String?

    fun groupId(groupName: String): UInt?
}

object PosixAccountDirectory : AccountDirectory {
    @OptIn(ExperimentalForeignApi::class)
    override fun homeDirectory(userId: UInt): String? =
        getpwuid(userId)?.pointed?.pw_dir?.toKString()

    @OptIn(ExperimentalForeignApi::class)
    override fun groupId(groupName: String): UInt? =
        getgrnam(groupName)?.pointed?.gr_gid
}

class HarmonSetup(
    private val commandRunner: CommandRunner = PosixCommandRunner,
    private val fileSystem: SetupFileSystem = PosixSetupFileSystem,
    private val prerequisiteChecker: SetupPrerequisiteChecker =
        SetupPrerequisiteChecker(commandRunner),
    private val accountDirectory: AccountDirectory = PosixAccountDirectory,
    private val effectiveUserId: () -> UInt = ::currentEffectiveUserId,
    private val effectiveGroupId: () -> UInt = ::currentEffectiveGroupId,
    private val homeDirectory: () -> String = ::currentHomeDirectory,
) {
    fun run(request: SetupRequest) {
        if (request.system) {
            runSystem(request)
        } else {
            runUser(request)
        }
    }

    private fun runUser(request: SetupRequest) {
        if (request.userId != null || request.groupId != null) {
            throw SetupException("--uid and --gid are valid only with --system")
        }
        val userId = effectiveUserId()
        SetupValidation.validateUserPhase(userId)
        val groupId = effectiveGroupId()
        val validated = prerequisiteChecker.check()
        val paths = UserSetup(
            validated = validated,
            userId = userId,
            groupId = groupId,
            home = homeDirectory(),
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
        println("Harmon is installed and running.")
        println("Config: ${paths.config}")
        println("Agent app: ${paths.appBundle}")
        println("Run 'harmon status' to verify the installed pair.")
    }

    private fun runSystem(request: SetupRequest) {
        val (targetUserId, targetGroupId) = SetupValidation.validateSystemPhase(
            effectiveUserId = effectiveUserId(),
            requestedUserId = request.userId,
            requestedGroupId = request.groupId,
        )
        val validated = prerequisiteChecker.check()
        val targetHome = accountDirectory.homeDirectory(targetUserId)
            ?: throw SetupException("No local account exists for uid $targetUserId")
        val wheelGroupId = accountDirectory.groupId("wheel")
            ?: throw SetupException("The macOS 'wheel' group was not found")
        SystemSetup(
            validated = validated,
            targetUserId = targetUserId,
            targetGroupId = targetGroupId,
            targetHome = targetHome,
            wheelGroupId = wheelGroupId,
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentEffectiveUserId(): UInt = geteuid()

@OptIn(ExperimentalForeignApi::class)
private fun currentEffectiveGroupId(): UInt = getegid()

@OptIn(ExperimentalForeignApi::class)
private fun currentHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw SetupException("HOME is not set")
