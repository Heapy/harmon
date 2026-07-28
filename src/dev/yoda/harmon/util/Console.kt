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

/**
 * What an error log says about [failure]: its message, or its class name when it carries none.
 *
 * A daemon logs a lot of caught throwables and an empty line helps nobody diagnose a launchd log.
 */
fun failureDescription(failure: Throwable): String =
    failure.message ?: failure::class.simpleName.orEmpty()

