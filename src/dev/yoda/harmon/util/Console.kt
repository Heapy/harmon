package dev.yoda.harmon.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
fun printError(message: String) {
    fputs("$message\n", stderr)
    fflush(stderr)
}

