package dev.yoda.harmon.setup

import dev.yoda.harmon.nativebridge.install.HMSpawnActions
import dev.yoda.harmon.nativebridge.install.hm_spawn
import dev.yoda.harmon.nativebridge.install.hm_spawn_actions_create
import dev.yoda.harmon.nativebridge.install.hm_spawn_actions_destroy
import dev.yoda.harmon.nativebridge.install.hm_wait_exit_code
import dev.yoda.harmon.util.systemErrorText
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import platform.posix.pid_tVar

data class CommandInvocation(
    val arguments: List<String>,
    val captureOutput: Boolean = true,
)

data class CommandResult(
    val invocation: CommandInvocation,
    val exitCode: Int,
    val output: String,
) {
    val successful: Boolean
        get() = exitCode == 0
}

class CommandExecutionException(
    val result: CommandResult,
) : IllegalStateException(
    buildString {
        append("Command failed with exit code ")
        append(result.exitCode)
        append(": ")
        append(result.invocation.arguments.joinToString(" "))
        result.output.trim().takeIf { it.isNotEmpty() }?.let { output ->
            appendLine()
            append(output)
        }
    },
)

interface CommandRunner {
    fun run(invocation: CommandInvocation): CommandResult
}

fun CommandRunner.run(
    arguments: List<String>,
    captureOutput: Boolean = true,
): CommandResult = run(CommandInvocation(arguments, captureOutput))

fun CommandRunner.requireSuccess(
    arguments: List<String>,
    captureOutput: Boolean = true,
): CommandResult {
    val result = run(arguments, captureOutput)
    if (!result.successful) {
        throw CommandExecutionException(result)
    }
    return result
}

object PosixCommandRunner : CommandRunner {
    @OptIn(ExperimentalForeignApi::class)
    override fun run(invocation: CommandInvocation): CommandResult {
        require(invocation.arguments.isNotEmpty()) { "command arguments must not be empty" }
        require(invocation.arguments.first().startsWith('/')) {
            "command executable must be an absolute path"
        }
        require(invocation.arguments.none { it.contains('\u0000') }) {
            "command arguments must not contain NUL"
        }

        return if (invocation.captureOutput) {
            runCaptured(invocation)
        } else {
            runInherited(invocation)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun runCaptured(invocation: CommandInvocation): CommandResult = memScoped {
        val descriptors = allocArray<IntVar>(2)
        if (pipe(descriptors) != 0) {
            throw IllegalStateException("Unable to create command output pipe: ${systemErrorText()}")
        }
        val readDescriptor = descriptors[0]
        val writeDescriptor = descriptors[1]
        val actionStatus = alloc<IntVar>()
        val actions = hm_spawn_actions_create(
            readDescriptor,
            writeDescriptor,
            actionStatus.ptr,
        )
        if (actions == null) {
            close(readDescriptor)
            close(writeDescriptor)
            checkSpawnAction(actionStatus.value, "initialize output capture")
            throw IllegalStateException("Unable to allocate command output capture")
        }
        try {
            val processId = spawn(invocation.arguments, actions)
            close(writeDescriptor)
            val output = readToEnd(readDescriptor)
            close(readDescriptor)
            val exitCode = waitFor(processId)
            CommandResult(invocation, exitCode, output)
        } catch (failure: Throwable) {
            close(readDescriptor)
            close(writeDescriptor)
            throw failure
        } finally {
            hm_spawn_actions_destroy(actions)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun runInherited(invocation: CommandInvocation): CommandResult = memScoped {
        val processId = spawn(invocation.arguments, actions = null)
        CommandResult(invocation, waitFor(processId), output = "")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun kotlinx.cinterop.MemScope.spawn(
        arguments: List<String>,
        actions: kotlinx.cinterop.CPointer<HMSpawnActions>?,
    ): Int {
        val processId = alloc<pid_tVar>()
        val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 1)
        arguments.forEachIndexed { index, argument ->
            argv[index] = argument.cstr.getPointer(this)
        }
        argv[arguments.size] = null
        val spawnResult = hm_spawn(
            processId.ptr,
            arguments.first(),
            actions,
            argv,
        )
        if (spawnResult != 0) {
            throw IllegalStateException(
                "Unable to start ${arguments.first()}: POSIX error $spawnResult",
            )
        }
        return processId.value
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun kotlinx.cinterop.MemScope.readToEnd(descriptor: Int): String {
        val chunks = mutableListOf<ByteArray>()
        var totalSize = 0
        val buffer = allocArray<ByteVar>(OUTPUT_BUFFER_SIZE)
        while (true) {
            val count = read(descriptor, buffer, OUTPUT_BUFFER_SIZE.toULong())
            when {
                count > 0 -> {
                    val chunk = buffer.readBytes(count.toInt())
                    chunks += chunk
                    totalSize += chunk.size
                }
                count == 0L -> break
                errno == EINTR -> continue
                else -> throw IllegalStateException(
                    "Unable to read command output: ${systemErrorText()}",
                )
            }
        }
        val bytes = ByteArray(totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, destinationOffset = offset)
            offset += chunk.size
        }
        return bytes.decodeToString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun kotlinx.cinterop.MemScope.waitFor(processId: Int): Int {
        val status = alloc<IntVar>()
        var waitResult: Int
        do {
            waitResult = waitpid(processId, status.ptr, 0)
        } while (waitResult < 0 && errno == EINTR)
        if (waitResult < 0) {
            throw IllegalStateException("Unable to wait for command: ${systemErrorText()}")
        }
        return hm_wait_exit_code(status.value)
    }

    private fun checkSpawnAction(result: Int, action: String) {
        if (result != 0) {
            throw IllegalStateException("Unable to $action for command: POSIX error $result")
        }
    }

    private const val OUTPUT_BUFFER_SIZE = 8192
}
