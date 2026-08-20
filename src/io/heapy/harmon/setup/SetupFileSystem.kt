package io.heapy.harmon.setup

import io.heapy.harmon.util.systemErrorText
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.R_OK
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.chmod
import platform.posix.chown
import platform.posix.errno
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.lstat
import platform.posix.readlink
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink

data class FileOwnership(
    val userId: UInt,
    val groupId: UInt,
)

data class InstalledFileAttributes(
    val mode: UInt,
    val ownership: FileOwnership? = null,
)

interface SetupFileSystem {
    fun ensureDirectory(
        path: String,
        attributes: InstalledFileAttributes,
    )

    fun copyFileAtomically(
        source: String,
        target: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)? = null,
    )

    fun writeTextAtomically(
        target: String,
        content: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)? = null,
    )

    fun setMode(path: String, mode: UInt)

    fun exists(path: String): Boolean

    fun isRegularFile(path: String): Boolean

    fun isReadable(path: String): Boolean

    fun isSymbolicLink(path: String): Boolean

    fun readSymbolicLink(path: String): String?

    fun readText(path: String): String

    fun removeFileIfExists(path: String)

    fun removeTreeIfExists(path: String)
}

object PosixSetupFileSystem : SetupFileSystem {
    private var temporarySequence = 0u

    @OptIn(ExperimentalForeignApi::class)
    override fun ensureDirectory(
        path: String,
        attributes: InstalledFileAttributes,
    ) {
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!created) {
            throw IllegalStateException("Unable to create directory '$path'")
        }
        applyAttributes(path, attributes)
    }

    override fun copyFileAtomically(
        source: String,
        target: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
    ) {
        publishAtomically(target, attributes, validateTemporaryFile) { temporary ->
            copyFile(source, temporary)
        }
    }

    override fun writeTextAtomically(
        target: String,
        content: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
    ) {
        publishAtomically(target, attributes, validateTemporaryFile) { temporary ->
            writeText(temporary, content)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun setMode(path: String, mode: UInt) {
        if (chmod(path, mode.toUShort()) != 0) {
            throw IllegalStateException("Unable to chmod '$path': ${systemErrorText()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun exists(path: String): Boolean = memScoped {
        val metadata = alloc<stat>()
        lstat(path, metadata.ptr) == 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun isRegularFile(path: String): Boolean = memScoped {
        val metadata = alloc<stat>()
        stat(path, metadata.ptr) == 0 &&
            (metadata.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    override fun isReadable(path: String): Boolean = access(path, R_OK) == 0

    @OptIn(ExperimentalForeignApi::class)
    override fun isSymbolicLink(path: String): Boolean = memScoped {
        val metadata = alloc<stat>()
        lstat(path, metadata.ptr) == 0 &&
            (metadata.st_mode.toInt() and S_IFMT) == S_IFLNK
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun readSymbolicLink(path: String): String? = memScoped {
        var capacity = INITIAL_LINK_BUFFER_SIZE
        while (capacity <= MAXIMUM_LINK_BUFFER_SIZE) {
            val buffer = allocArray<ByteVar>(capacity)
            val length = readlink(path, buffer, capacity.toULong())
            if (length < 0) {
                return@memScoped null
            }
            if (length < capacity) {
                return@memScoped buffer.readBytes(length.toInt()).decodeToString()
            }
            capacity *= 2
        }
        throw IllegalStateException("Symbolic link target is too long: '$path'")
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun readText(path: String): String {
        val file = fopen(path, "rb")
            ?: throw IllegalStateException("Unable to open '$path': ${systemErrorText()}")
        return try {
            readAll(file)
        } finally {
            fclose(file)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun removeFileIfExists(path: String) {
        if (unlink(path) != 0 && errno != ENOENT) {
            throw IllegalStateException("Unable to remove '$path': ${systemErrorText()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun removeTreeIfExists(path: String) {
        if (!exists(path)) {
            return
        }
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!NSFileManager.defaultManager.removeItemAtPath(path, error.ptr)) {
                // Without the cause an aborted purge reports only a path, and a locked file, a
                // root-owned leftover, and a protected directory all look the same.
                val cause = error.value
                val detail = cause?.let {
                    "${it.localizedDescription} [${it.domain} ${it.code}]"
                } ?: "NSFileManager reported no error"
                throw IllegalStateException("Unable to remove '$path': $detail")
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun publishAtomically(
        target: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
        writeTemporaryFile: (String) -> Unit,
    ) {
        temporarySequence += 1u
        val temporary = "$target.${getpid()}.$temporarySequence.tmp"
        removeFileIfExists(temporary)
        try {
            writeTemporaryFile(temporary)
            applyAttributes(temporary, attributes)
            validateTemporaryFile?.invoke(temporary)
            if (rename(temporary, target) != 0) {
                throw IllegalStateException(
                    "Unable to publish '$target': ${systemErrorText()}",
                )
            }
        } catch (failure: Throwable) {
            removeFileIfExists(temporary)
            throw failure
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun applyAttributes(
        path: String,
        attributes: InstalledFileAttributes,
    ) {
        if (chmod(path, attributes.mode.toUShort()) != 0) {
            throw IllegalStateException("Unable to chmod '$path': ${systemErrorText()}")
        }
        attributes.ownership?.let { ownership ->
            if (chown(path, ownership.userId, ownership.groupId) != 0) {
                throw IllegalStateException("Unable to chown '$path': ${systemErrorText()}")
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun copyFile(source: String, target: String) = memScoped {
        val input = fopen(source, "rb")
            ?: throw IllegalStateException("Unable to open '$source': ${systemErrorText()}")
        val output = fopen(target, "wb")
        if (output == null) {
            fclose(input)
            throw IllegalStateException("Unable to create '$target': ${systemErrorText()}")
        }
        try {
            val buffer = allocArray<ByteVar>(FILE_BUFFER_SIZE)
            while (true) {
                val count = fread(buffer, 1uL, FILE_BUFFER_SIZE.toULong(), input)
                if (count > 0uL && fwrite(buffer, 1uL, count, output) != count) {
                    throw IllegalStateException(
                        "Unable to write '$target': ${systemErrorText()}",
                    )
                }
                if (count < FILE_BUFFER_SIZE.toULong()) {
                    if (ferror(input) != 0) {
                        throw IllegalStateException(
                            "Unable to read '$source': ${systemErrorText()}",
                        )
                    }
                    break
                }
            }
            flush(output, target)
        } finally {
            fclose(input)
            fclose(output)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeText(path: String, content: String) {
        val output = fopen(path, "wb")
            ?: throw IllegalStateException("Unable to create '$path': ${systemErrorText()}")
        try {
            val bytes = content.encodeToByteArray()
            val written = if (bytes.isEmpty()) {
                0uL
            } else {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), output)
                }
            }
            if (written != bytes.size.toULong()) {
                throw IllegalStateException("Unable to write '$path': ${systemErrorText()}")
            }
            flush(output, path)
        } finally {
            fclose(output)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun flush(file: kotlinx.cinterop.CPointer<platform.posix.FILE>, path: String) {
        if (fflush(file) != 0 || fsync(fileno(file)) != 0) {
            throw IllegalStateException("Unable to flush '$path': ${systemErrorText()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readAll(file: kotlinx.cinterop.CPointer<platform.posix.FILE>): String = memScoped {
        val chunks = mutableListOf<ByteArray>()
        var totalSize = 0
        val buffer = allocArray<ByteVar>(FILE_BUFFER_SIZE)
        while (true) {
            val count = fread(buffer, 1uL, FILE_BUFFER_SIZE.toULong(), file)
            if (count > 0uL) {
                val chunk = buffer.readBytes(count.toInt())
                chunks += chunk
                totalSize += chunk.size
            }
            if (count < FILE_BUFFER_SIZE.toULong()) {
                if (ferror(file) != 0) {
                    throw IllegalStateException("Unable to read file: ${systemErrorText()}")
                }
                break
            }
        }
        val bytes = ByteArray(totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, destinationOffset = offset)
            offset += chunk.size
        }
        bytes.decodeToString()
    }

    private const val FILE_BUFFER_SIZE = 64 * 1024
    private const val INITIAL_LINK_BUFFER_SIZE = 256
    private const val MAXIMUM_LINK_BUFFER_SIZE = 1024 * 1024
}

class AtomicPlistWriter(
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun write(
        target: String,
        job: LaunchdJob,
        attributes: InstalledFileAttributes,
    ) {
        fileSystem.writeTextAtomically(
            target = target,
            content = job.xml(),
            attributes = attributes,
            validateTemporaryFile = { temporary ->
                commandRunner.requireSuccess(
                    listOf("/usr/bin/plutil", "-lint", temporary),
                )
            },
        )
    }
}
