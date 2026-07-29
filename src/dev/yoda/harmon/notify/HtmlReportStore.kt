package dev.yoda.harmon.notify

import dev.yoda.harmon.util.systemErrorText
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.getenv
import platform.posix.rename
import platform.posix.unlink

internal class HtmlReportStore(
    private val homeDirectory: String = currentHomeDirectory(),
) {
    val reportPath: String =
        "$homeDirectory/Library/Application Support/Harmon/Reports/latest.html"

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun write(html: String): String = memScoped {
        val reportDirectory = reportPath.substringBeforeLast('/')
        val error = alloc<ObjCObjectVar<NSError?>>()
        val fileManager = NSFileManager.defaultManager

        if (
            !fileManager.createDirectoryAtPath(
                path = reportDirectory,
                withIntermediateDirectories = true,
                attributes = null,
                error = error.ptr,
            )
        ) {
            throw IllegalStateException(
                error.value?.localizedDescription
                    ?: "Unable to create report directory $reportDirectory",
            )
        }

        writeAtomically(html)
        reportPath
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeAtomically(html: String) {
        val temporaryPath = "$reportPath.${getpid()}.tmp"
        val file = fopen(temporaryPath, "wb")
            ?: throw fileFailure("Unable to open temporary HTML report")
        var closed = false
        try {
            if (chmod(temporaryPath, (S_IRUSR or S_IWUSR).toUShort()) != 0) {
                throw fileFailure("Unable to protect temporary HTML report")
            }
            val bytes = html.encodeToByteArray()
            val written = if (bytes.isEmpty()) {
                0uL
            } else {
                bytes.usePinned { pinned ->
                    fwrite(
                        pinned.addressOf(0),
                        1uL,
                        bytes.size.toULong(),
                        file,
                    )
                }
            }
            if (written != bytes.size.toULong()) {
                throw fileFailure("Unable to write complete HTML report")
            }
            if (fflush(file) != 0 || fsync(fileno(file)) != 0) {
                throw fileFailure("Unable to flush HTML report")
            }
            val closeResult = fclose(file)
            closed = true
            if (closeResult != 0) {
                throw fileFailure("Unable to close HTML report")
            }
            if (rename(temporaryPath, reportPath) != 0) {
                throw fileFailure("Unable to publish HTML report")
            }
        } catch (failure: Throwable) {
            if (!closed) {
                fclose(file)
            }
            unlink(temporaryPath)
            throw failure
        }
    }

    private fun fileFailure(message: String): IllegalStateException =
        IllegalStateException("$message: ${systemErrorText()}")

    private companion object {
        @OptIn(ExperimentalForeignApi::class)
        fun currentHomeDirectory(): String =
            getenv("HOME")?.toKString()
                ?: throw IllegalStateException("HOME is not set")
    }
}
