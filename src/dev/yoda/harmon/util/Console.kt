package dev.yoda.harmon.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
fun printError(message: String) {
    fputs("$message\n", stderr)
    fflush(stderr)
}

/**
 * What an error log says about [failure]: its message, or its class name when it carries none.
 *
 * A daemon logs a lot of caught throwables and an empty line helps nobody diagnose a launchd log.
 */
fun failureDescription(failure: Throwable): String =
    failure.message ?: failure::class.simpleName.orEmpty()

/**
 * What the current `errno` says, as text, falling back to the number when the C library has no
 * wording for it.
 *
 * Reads `errno` itself rather than taking it, so it has to be called before anything else that
 * could set it — which in practice means straight inside the `if` that found the syscall failed.
 */
@OptIn(ExperimentalForeignApi::class)
fun systemErrorText(): String {
    val code = errno
    return strerror(code)?.toKString() ?: "error $code"
}

