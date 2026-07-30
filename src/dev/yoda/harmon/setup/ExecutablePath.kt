package dev.yoda.harmon.setup

import dev.yoda.harmon.nativebridge.install._NSGetExecutablePath
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.R_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.X_OK
import platform.posix.access
import platform.posix.free
import platform.posix.realpath
import platform.posix.stat

object ExecutablePath {
    @OptIn(ExperimentalForeignApi::class)
    fun current(): String = memScoped {
        var capacity = INITIAL_EXECUTABLE_PATH_CAPACITY
        while (true) {
            val size = alloc<UIntVar>()
            size.value = capacity
            val buffer = allocArray<ByteVar>(capacity.toInt())
            if (_NSGetExecutablePath(buffer, size.ptr) == 0) {
                val unresolved = buffer.toKString()
                return@memScoped PosixResourcePathProbe.realPath(unresolved)
                    ?: throw ResourceLocationException(
                        "Unable to resolve the running executable path '$unresolved'",
                    )
            }
            if (size.value <= capacity) {
                throw ResourceLocationException("Unable to read the running executable path")
            }
            capacity = size.value
        }
        throw ResourceLocationException("Unable to read the running executable path")
    }

    private const val INITIAL_EXECUTABLE_PATH_CAPACITY: UInt = 1024u
}

object PosixResourcePathProbe : ResourcePathProbe {
    @OptIn(ExperimentalForeignApi::class)
    override fun realPath(path: String): String? {
        val resolved = realpath(path, null) ?: return null
        return try {
            resolved.toKString()
        } finally {
            free(resolved)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun isRegularFile(path: String): Boolean = memScoped {
        val metadata = alloc<stat>()
        stat(path, metadata.ptr) == 0 &&
            (metadata.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    override fun isReadable(path: String): Boolean = access(path, R_OK) == 0

    override fun isExecutable(path: String): Boolean = access(path, X_OK) == 0
}
