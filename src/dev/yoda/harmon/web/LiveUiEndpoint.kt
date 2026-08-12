package dev.yoda.harmon.web

import dev.yoda.harmon.nativebridge.http.HMHttpResult
import dev.yoda.harmon.nativebridge.http.hm_http_get_status
import dev.yoda.harmon.nativebridge.http.hm_http_global_init
import dev.yoda.harmon.setup.InstallModes
import dev.yoda.harmon.setup.InstalledFileAttributes
import dev.yoda.harmon.setup.PosixSetupFileSystem
import dev.yoda.harmon.setup.SetupFileSystem
import dev.yoda.harmon.setup.UserSetupPaths
import dev.yoda.harmon.util.systemErrorText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv

data class LiveUiEndpoint(
    val port: Int,
    val token: String,
) {
    init {
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        require(token.matches(TOKEN_PATTERN)) { "token must be 64 lowercase hexadecimal characters" }
    }

    val url: String
        get() = "http://127.0.0.1:$port/?token=$token"

    val apiUrl: String
        get() = "http://127.0.0.1:$port/api/live?token=$token"

    fun encode(): String = "port=$port\ntoken=$token\n"

    companion object {
        fun parse(content: String): LiveUiEndpoint {
            val values = buildMap {
                for (line in content.lineSequence().filter(String::isNotBlank)) {
                    val separator = line.indexOf('=')
                    require(separator > 0) { "invalid live UI endpoint line" }
                    val key = line.substring(0, separator)
                    require(key !in this) { "duplicate live UI endpoint key" }
                    put(key, line.substring(separator + 1))
                }
            }
            require(values.keys == setOf("port", "token")) { "invalid live UI endpoint keys" }
            val port = values.getValue("port").toIntOrNull()
                ?: throw IllegalArgumentException("invalid live UI endpoint port")
            return LiveUiEndpoint(port, values.getValue("token"))
        }

        private val TOKEN_PATTERN = Regex("[0-9a-f]{64}")
    }
}

class LiveUiEndpointStore(
    homeDirectory: String = currentHomeDirectory(),
    private val fileSystem: SetupFileSystem = PosixSetupFileSystem,
) {
    private val paths = UserSetupPaths.forHome(homeDirectory)
    private val directory = paths.supportDirectory
    val path: String = paths.liveUiEndpoint

    fun publish(endpoint: LiveUiEndpoint) {
        fileSystem.ensureDirectory(
            directory,
            InstalledFileAttributes(InstallModes.USER_DIRECTORY),
        )
        fileSystem.writeTextAtomically(
            target = path,
            content = endpoint.encode(),
            attributes = InstalledFileAttributes(InstallModes.USER_SECRET),
        )
    }

    fun read(): LiveUiEndpoint {
        if (!fileSystem.isRegularFile(path) || !fileSystem.isReadable(path)) {
            throw IllegalStateException("Harmon live UI is not running; endpoint is missing at $path")
        }
        return try {
            LiveUiEndpoint.parse(fileSystem.readText(path))
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("Harmon live UI endpoint is invalid at $path", failure)
        }
    }

    fun removeIfCurrent(endpoint: LiveUiEndpoint) {
        val current = try {
            read()
        } catch (_: IllegalStateException) {
            return
        }
        if (current == endpoint) fileSystem.removeFileIfExists(path)
    }

    fun remove() {
        fileSystem.removeFileIfExists(path)
    }
}

object LiveUiLauncher {
    fun open(store: LiveUiEndpointStore = LiveUiEndpointStore()) {
        val endpoint = store.read()
        if (!liveUiEndpointResponds(endpoint)) {
            store.removeIfCurrent(endpoint)
            throw IllegalStateException("Harmon live UI is not responding; its stale endpoint was removed")
        }
        val url = NSURL.URLWithString(endpoint.url)
            ?: throw IllegalStateException("Unable to create the live UI URL")
        if (!NSWorkspace.sharedWorkspace.openURL(url)) {
            throw IllegalStateException("Unable to open the Harmon live UI")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun liveUiEndpointResponds(endpoint: LiveUiEndpoint): Boolean =
    LiveUiEndpointProbe.responds(endpoint)

@OptIn(ExperimentalForeignApi::class)
private object LiveUiEndpointProbe {
    private val initialized = hm_http_global_init() == 0

    fun responds(endpoint: LiveUiEndpoint): Boolean = memScoped {
        if (!initialized) return@memScoped false
        val result = alloc<HMHttpResult>()
        hm_http_get_status(endpoint.apiUrl, LIVE_UI_PROBE_TIMEOUT_SECONDS, result.ptr) == 0 &&
            result.status_code == 200L
    }
}

@OptIn(ExperimentalForeignApi::class)
fun generateLiveUiToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    val file = fopen("/dev/urandom", "rb")
        ?: throw IllegalStateException("Unable to open /dev/urandom: ${systemErrorText()}")
    try {
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
        }
        if (read != bytes.size.toULong()) {
            throw IllegalStateException("Unable to read a live UI token from /dev/urandom")
        }
    } finally {
        fclose(file)
    }
    return buildString(TOKEN_BYTES * 2) {
        for (byte in bytes) append(byte.toUByte().toString(radix = 16).padStart(2, '0'))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw IllegalStateException("HOME is not set")

private const val TOKEN_BYTES = 32
private const val LIVE_UI_PROBE_TIMEOUT_SECONDS = 2L
