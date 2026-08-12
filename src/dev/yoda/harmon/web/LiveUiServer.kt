package dev.yoda.harmon.web

import dev.yoda.harmon.report.ProcessPage
import dev.yoda.harmon.util.systemErrorText
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.INADDR_LOOPBACK
import platform.posix.IPPROTO_TCP
import platform.posix.MSG_NOSIGNAL
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SOMAXCONN
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import platform.posix.usleep
import kotlin.time.TimeSource

class LiveUiState(initialJson: String) {
    private val lock = NSLock()
    private var json = initialJson

    fun update(nextJson: String) {
        lock.lock()
        try {
            json = nextJson
        } finally {
            lock.unlock()
        }
    }

    fun currentJson(): String {
        lock.lock()
        return try {
            json
        } finally {
            lock.unlock()
        }
    }
}

class LiveUiServer(
    private val state: LiveUiState,
    private val token: String,
    private val page: String = ProcessPage.document(payloadJson = null, mode = "live"),
    private val onWatch: () -> Unit = {},
    private val logError: (String) -> Unit,
) {
    private val lifecycleLock = NSLock()
    private val queue = dispatch_queue_create("dev.yoda.harmon.web.accept", null)
    private var listener = -1

    init {
        require(token.matches(TOKEN_PATTERN)) { "token must be 64 lowercase hexadecimal characters" }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun start(): Int {
        val descriptor = openListener()
        val port = try {
            boundPort(descriptor)
        } catch (failure: Throwable) {
            close(descriptor)
            throw failure
        }
        try {
            lifecycleLock.lock()
            try {
                check(listener < 0) { "live UI server is already running" }
                listener = descriptor
            } finally {
                lifecycleLock.unlock()
            }
        } catch (failure: Throwable) {
            close(descriptor)
            throw failure
        }
        dispatch_async(queue) { acceptLoop(descriptor) }
        return port
    }

    @OptIn(ExperimentalForeignApi::class)
    fun stop() {
        lifecycleLock.lock()
        val descriptor = try {
            val current = listener
            listener = -1
            current
        } finally {
            lifecycleLock.unlock()
        }
        if (descriptor >= 0) close(descriptor)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun acceptLoop(descriptor: Int) {
        while (isActive(descriptor)) {
            val client = accept(descriptor, null, null)
            if (client >= 0) {
                serve(client)
            } else if (errno != EINTR && isActive(descriptor)) {
                logError("live UI accept failed: ${systemErrorText()}")
                usleep(ACCEPT_RETRY_MICROSECONDS)
            }
        }
    }

    private fun isActive(descriptor: Int): Boolean {
        lifecycleLock.lock()
        return try {
            listener == descriptor
        } finally {
            lifecycleLock.unlock()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun serve(client: Int) {
        try {
            protectClientSocket(client)
            val request = readRequest(client)
            val response = request?.let(::route) ?: HttpResponse.badRequest()
            writeResponse(client, response)
        } catch (failure: Throwable) {
            logError("live UI request failed: ${failure.message ?: failure::class.simpleName}")
        } finally {
            close(client)
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return HttpResponse.methodNotAllowed()
        if (!request.target.startsWith('/')) return HttpResponse.badRequest()

        val path = request.target.substringBefore('?')
        val suppliedToken = queryParameter(request.target, "token")
        if (!secureEquals(token, suppliedToken.orEmpty())) return HttpResponse.forbidden()

        return when (path) {
            "/" -> HttpResponse.ok("text/html; charset=utf-8", page)
            "/api/live" -> {
                if (liveUiWatchRequested(request.target)) onWatch()
                HttpResponse.ok("application/json; charset=utf-8", state.currentJson())
            }
            else -> HttpResponse.notFound()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readRequest(client: Int): HttpRequest? = memScoped {
        val buffer = allocArray<ByteVar>(REQUEST_CHUNK_BYTES)
        val receiveTimeout = alloc<timeval>()
        val bytes = ArrayList<Byte>(REQUEST_CHUNK_BYTES)
        val startedAt = TimeSource.Monotonic.markNow()
        while (bytes.size < MAX_REQUEST_BYTES) {
            val remainingMicroseconds =
                REQUEST_DEADLINE_MICROSECONDS - startedAt.elapsedNow().inWholeMicroseconds
            if (remainingMicroseconds <= 0) return@memScoped null
            receiveTimeout.tv_sec = (remainingMicroseconds / MICROSECONDS_PER_SECOND).convert()
            receiveTimeout.tv_usec = (remainingMicroseconds % MICROSECONDS_PER_SECOND).convert()
            if (
                setsockopt(
                    client,
                    SOL_SOCKET,
                    SO_RCVTIMEO,
                    receiveTimeout.ptr,
                    sizeOf<timeval>().convert(),
                ) != 0
            ) {
                return@memScoped null
            }
            val read = recv(client, buffer, REQUEST_CHUNK_BYTES.convert(), 0).toInt()
            if (read <= 0) return@memScoped null
            for (byte in buffer.readBytes(read)) bytes.add(byte)
            if (bytes.hasHeaderBoundary()) break
        }
        if (!bytes.hasHeaderBoundary()) return@memScoped null

        val firstLine = bytes.toByteArray().decodeToString().lineSequence().firstOrNull()
            ?: return@memScoped null
        val parts = firstLine.trimEnd('\r').split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return@memScoped null
        HttpRequest(parts[0], parts[1])
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeResponse(client: Int, response: HttpResponse) {
        val body = response.body.encodeToByteArray()
        val header = buildString {
            append("HTTP/1.1 ${response.status}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline' data:; ")
            append("style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            append("Connection: close\r\n\r\n")
        }.encodeToByteArray()
        sendAll(client, header)
        sendAll(client, body)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun sendAll(client: Int, bytes: ByteArray) {
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val sent = send(
                    client,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                    MSG_NOSIGNAL,
                ).toInt()
                if (sent > 0) {
                    offset += sent
                } else if (sent < 0 && errno == EINTR) {
                    continue
                } else {
                    return@usePinned
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun openListener(): Int = memScoped {
        val descriptor = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        if (descriptor < 0) throw IllegalStateException("Unable to create live UI socket: ${systemErrorText()}")
        try {
            val enabled = alloc<IntVar>()
            enabled.value = 1
            if (
                setsockopt(
                    descriptor,
                    SOL_SOCKET,
                    SO_REUSEADDR,
                    enabled.ptr,
                    sizeOf<IntVar>().convert(),
                ) != 0
            ) {
                throw IllegalStateException("Unable to configure live UI socket: ${systemErrorText()}")
            }
            fcntl(descriptor, F_SETFD, FD_CLOEXEC)

            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            address.sin_addr.s_addr = hostToNetwork32(INADDR_LOOPBACK)
            if (
                bind(
                    descriptor,
                    address.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().convert(),
                ) != 0
            ) {
                throw IllegalStateException("Unable to bind live UI socket: ${systemErrorText()}")
            }
            if (listen(descriptor, SOMAXCONN) != 0) {
                throw IllegalStateException("Unable to listen for live UI connections: ${systemErrorText()}")
            }
            descriptor
        } catch (failure: Throwable) {
            close(descriptor)
            throw failure
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun boundPort(descriptor: Int): Int = memScoped {
        val address = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        if (getsockname(descriptor, address.ptr.reinterpret(), length.ptr) != 0) {
            throw IllegalStateException("Unable to read live UI port: ${systemErrorText()}")
        }
        networkToHost16(address.sin_port).toInt()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun protectClientSocket(client: Int) = memScoped {
        val enabled = alloc<IntVar>()
        enabled.value = 1
        setsockopt(
            client,
            SOL_SOCKET,
            SO_NOSIGPIPE,
            enabled.ptr,
            sizeOf<IntVar>().convert(),
        )
        val timeout = alloc<timeval>()
        timeout.tv_sec = REQUEST_TIMEOUT_SECONDS
        timeout.tv_usec = 0
        setsockopt(
            client,
            SOL_SOCKET,
            SO_RCVTIMEO,
            timeout.ptr,
            sizeOf<timeval>().convert(),
        )
        setsockopt(
            client,
            SOL_SOCKET,
            SO_SNDTIMEO,
            timeout.ptr,
            sizeOf<timeval>().convert(),
        )
    }

    private data class HttpRequest(val method: String, val target: String)

    private data class HttpResponse(
        val status: String,
        val contentType: String,
        val body: String,
    ) {
        companion object {
            fun ok(contentType: String, body: String): HttpResponse =
                HttpResponse("200 OK", contentType, body)

            fun badRequest(): HttpResponse = text("400 Bad Request", "Bad request")

            fun forbidden(): HttpResponse = text("403 Forbidden", "Forbidden")

            fun notFound(): HttpResponse = text("404 Not Found", "Not found")

            fun methodNotAllowed(): HttpResponse = text("405 Method Not Allowed", "Method not allowed")

            private fun text(status: String, body: String): HttpResponse =
                HttpResponse(status, "text/plain; charset=utf-8", body)
        }
    }

    private companion object {
        val TOKEN_PATTERN = Regex("[0-9a-f]{64}")
        const val REQUEST_CHUNK_BYTES = 2_048
        const val MAX_REQUEST_BYTES = 16_384
        const val REQUEST_TIMEOUT_SECONDS = 2L
        const val REQUEST_DEADLINE_MICROSECONDS = 2_000_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val ACCEPT_RETRY_MICROSECONDS = 100_000u
    }
}

fun liveUiWatchRequested(target: String): Boolean =
    target.substringBefore('?') == "/api/live" && queryParameter(target, "watch") == "1"

private fun queryParameter(target: String, name: String): String? =
    target.substringAfter('?', "")
        .split('&')
        .firstNotNullOfOrNull { part ->
            part.substringBefore('=').takeIf { it == name }
                ?.let { part.substringAfter('=', "") }
        }

fun secureEquals(expected: String, supplied: String): Boolean {
    var difference = expected.length xor supplied.length
    val longest = maxOf(expected.length, supplied.length)
    for (index in 0 until longest) {
        val left = expected.getOrElse(index) { '\u0000' }.code
        val right = supplied.getOrElse(index) { '\u0000' }.code
        difference = difference or (left xor right)
    }
    return difference == 0
}

private fun ArrayList<Byte>.hasHeaderBoundary(): Boolean {
    if (size < 4) return false
    for (index in 3 until size) {
        if (
            this[index - 3] == '\r'.code.toByte() &&
            this[index - 2] == '\n'.code.toByte() &&
            this[index - 1] == '\r'.code.toByte() &&
            this[index] == '\n'.code.toByte()
        ) {
            return true
        }
    }
    return false
}

private fun hostToNetwork32(value: UInt): UInt =
    ((value and 0x000000ffu) shl 24) or
        ((value and 0x0000ff00u) shl 8) or
        ((value and 0x00ff0000u) shr 8) or
        ((value and 0xff000000u) shr 24)

private fun networkToHost16(value: UShort): UShort =
    (((value.toUInt() and 0x00ffu) shl 8) or ((value.toUInt() and 0xff00u) shr 8)).toUShort()
