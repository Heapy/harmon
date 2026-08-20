package io.heapy.harmon.util

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

fun failureDescription(failure: Throwable): String =
    failure.message ?: failure::class.simpleName.orEmpty()

@OptIn(ExperimentalForeignApi::class)
/** Read immediately after a failed syscall, before another call can replace `errno`. */
fun systemErrorText(): String {
    val code = errno
    return strerror(code)?.toKString() ?: "error $code"
}
