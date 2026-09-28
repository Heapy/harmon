package io.heapy.harmon.web

import io.heapy.harmon.nativebridge.http.HMHttpResult
import io.heapy.harmon.nativebridge.http.hm_http_get_status
import io.heapy.harmon.nativebridge.http.hm_http_global_init
import io.heapy.harmon.setup.InstallModes
import io.heapy.harmon.setup.InstalledFileAttributes
import io.heapy.harmon.setup.PosixSetupFileSystem
import io.heapy.harmon.setup.SetupFileSystem
import io.heapy.harmon.setup.UserSetupPaths
import io.heapy.harmon.util.systemErrorText
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
        get() = "http://127.0.0.1:$port/#token=$token"

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
    fun open(store: LiveUiEndpointStore, probe: (LiveUiEndpoint) -> LiveUiProbeOutcome) {
        val endpoint = store.read()
        val address = "127.0.0.1:${endpoint.port}"
        when (val state = classifyLiveUiProbe(probe(endpoint))) {
            LiveUiEndpointState.Responds -> Unit
            is LiveUiEndpointState.Stale -> {
                store.removeIfCurrent(endpoint)
                val cause = when (state) {
                    LiveUiEndpointState.NothingListens -> "nothing listens on $address"
                    is LiveUiEndpointState.AnotherProcess ->
                        "another process answered on $address with HTTP ${state.httpStatus}" +
                            if (state.httpStatus == 200L) " but no $LIVE_UI_HARMON_HEADER header" else ""
                }
                throw IllegalStateException(
                    "Harmon live UI is not running: $cause; removed its stale endpoint. Run 'harmon status'.",
                )
            }
            is LiveUiEndpointState.NoAnswer -> throw IllegalStateException(
                "Harmon live UI did not answer on $address within $LIVE_UI_PROBE_TIMEOUT_SECONDS s " +
                    "(${state.error}). The agent may be busy; try again or run 'harmon status'.",
            )
        }
        val url = NSURL.URLWithString(endpoint.url)
            ?: throw IllegalStateException("Unable to create the live UI URL")
        if (!NSWorkspace.sharedWorkspace.openURL(url)) {
            throw IllegalStateException("Unable to open the Harmon live UI")
        }
    }
}

data class LiveUiProbeOutcome(
    val curlCode: Int,
    val httpStatus: Long,
    val harmonHeader: Boolean,
    val error: String,
)

sealed interface LiveUiEndpointState {
    data object Responds : LiveUiEndpointState

    sealed interface Stale : LiveUiEndpointState

    data object NothingListens : Stale

    data class AnotherProcess(val httpStatus: Long) : Stale

    data class NoAnswer(val error: String) : LiveUiEndpointState
}

fun classifyLiveUiProbe(outcome: LiveUiProbeOutcome): LiveUiEndpointState = when {
    outcome.curlCode == CURLE_COULDNT_CONNECT -> LiveUiEndpointState.NothingListens
    outcome.curlCode != CURLE_OK || outcome.httpStatus <= 0L -> LiveUiEndpointState.NoAnswer(outcome.error)
    outcome.httpStatus == 200L && outcome.harmonHeader -> LiveUiEndpointState.Responds
    else -> LiveUiEndpointState.AnotherProcess(outcome.httpStatus)
}

fun liveUiEndpointResponds(endpoint: LiveUiEndpoint): Boolean =
    classifyLiveUiProbe(probeLiveUiEndpoint(endpoint)) == LiveUiEndpointState.Responds

fun probeLiveUiEndpoint(endpoint: LiveUiEndpoint): LiveUiProbeOutcome =
    LiveUiEndpointProbe.probe(endpoint)

@OptIn(ExperimentalForeignApi::class)
private object LiveUiEndpointProbe {
    private val initialization = hm_http_global_init()

    fun probe(endpoint: LiveUiEndpoint): LiveUiProbeOutcome = memScoped {
        if (initialization != CURLE_OK) {
            return@memScoped LiveUiProbeOutcome(initialization, 0L, false, "libcurl initialization failed")
        }
        val result = alloc<HMHttpResult>()
        hm_http_get_status(endpoint.apiUrl, LIVE_UI_PROBE_TIMEOUT_SECONDS, result.ptr)
        LiveUiProbeOutcome(
            curlCode = result.curl_code,
            httpStatus = result.status_code,
            harmonHeader = result.harmon_live_header != 0,
            error = result.error.toKString(),
        )
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
private const val CURLE_OK = 0
private const val CURLE_COULDNT_CONNECT = 7
