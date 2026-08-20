package io.heapy.harmon.web

import io.heapy.harmon.report.ProcessPage
import io.heapy.harmon.util.systemErrorText
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSLock
import platform.darwin.DISPATCH_QUEUE_CONCURRENT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.ENOTCONN
import platform.posix.EWOULDBLOCK
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.INADDR_LOOPBACK
import platform.posix.IPPROTO_TCP
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.SHUT_RDWR
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
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import platform.posix.usleep
import platform.posix.write
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

enum class LiveHttpSocketKind {
    LISTENER,
    CLIENT,
}

/** Returns null after applying every required option, or the first setup error. */
@OptIn(ExperimentalForeignApi::class)
fun configureLiveHttpSocket(descriptor: Int, kind: LiveHttpSocketKind): String? = memScoped {
    val enabled = alloc<IntVar>()
    enabled.value = 1
    if (kind == LiveHttpSocketKind.LISTENER) {
        val failure = setBooleanSocketOption(descriptor, SO_REUSEADDR, enabled)
        if (failure != null) return@memScoped "set SO_REUSEADDR: $failure"
    }
    setBooleanSocketOption(descriptor, SO_NOSIGPIPE, enabled)?.let {
        return@memScoped "set SO_NOSIGPIPE: $it"
    }

    val timeout = alloc<timeval>()
    timeout.tv_sec = REQUEST_TIMEOUT_SECONDS
    timeout.tv_usec = 0
    if (
        setsockopt(
            descriptor,
            SOL_SOCKET,
            SO_RCVTIMEO,
            timeout.ptr,
            sizeOf<timeval>().convert(),
        ) != 0
    ) {
        return@memScoped "set SO_RCVTIMEO: ${systemErrorText()}"
    }
    if (
        setsockopt(
            descriptor,
            SOL_SOCKET,
            SO_SNDTIMEO,
            timeout.ptr,
            sizeOf<timeval>().convert(),
        ) != 0
    ) {
        return@memScoped "set SO_SNDTIMEO: ${systemErrorText()}"
    }

    val descriptorFlags = fcntl(descriptor, F_GETFD)
    if (descriptorFlags < 0) return@memScoped "read descriptor flags: ${systemErrorText()}"
    if (fcntl(descriptor, F_SETFD, descriptorFlags or FD_CLOEXEC) != 0) {
        return@memScoped "set FD_CLOEXEC: ${systemErrorText()}"
    }

    val statusFlags = fcntl(descriptor, F_GETFL)
    if (statusFlags < 0) return@memScoped "read descriptor status flags: ${systemErrorText()}"
    val nextStatusFlags = when (kind) {
        LiveHttpSocketKind.LISTENER -> statusFlags or O_NONBLOCK
        LiveHttpSocketKind.CLIENT -> statusFlags and O_NONBLOCK.inv()
    }
    if (fcntl(descriptor, F_SETFL, nextStatusFlags) != 0) {
        return@memScoped "set descriptor blocking mode: ${systemErrorText()}"
    }
    null
}

class LiveUiServer(
    private val state: LiveUiState,
    private val token: String,
    private val page: String = ProcessPage.document(payloadJson = null, mode = "live"),
    private val onWatch: () -> Unit = {},
    private val logError: (String) -> Unit,
    private val configureSocket: (Int, LiveHttpSocketKind) -> String? = ::configureLiveHttpSocket,
) {
    private val lifecycle = LiveHttpCondition()
    private val acceptQueue = dispatch_queue_create("io.heapy.harmon.web.accept", null)
    private val workerQueue = dispatch_queue_create(
        "io.heapy.harmon.web.worker",
        DISPATCH_QUEUE_CONCURRENT,
    )
    private var currentRun: LiveHttpRun? = null
    private var nextGeneration = 1uL

    init {
        require(token.matches(TOKEN_PATTERN)) { "token must be 64 lowercase hexadecimal characters" }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun start(): Int {
        val resources = openRunResources()
        val run: LiveHttpRun
        try {
            lifecycle.lock()
            try {
                check(currentRun == null) {
                    if (currentRun?.state == LiveHttpRunState.CLOSING) {
                        "previous live UI server run is still draining"
                    } else {
                        "live UI server is already running"
                    }
                }
                run = LiveHttpRun(
                    generation = nextGeneration,
                    listener = resources.listener,
                    wakeRead = resources.wakeRead,
                    wakeWrite = resources.wakeWrite,
                    port = resources.port,
                )
                nextGeneration = if (nextGeneration == ULong.MAX_VALUE) 1uL else nextGeneration + 1uL
                currentRun = run
            } finally {
                lifecycle.unlock()
            }
        } catch (failure: Throwable) {
            close(resources.listener)
            close(resources.wakeRead)
            close(resources.wakeWrite)
            throw failure
        }
        dispatch_async(acceptQueue) { acceptLoop(run) }
        return run.port
    }

    @OptIn(ExperimentalForeignApi::class)
    fun stop() {
        val deferredDiagnostics = mutableListOf<Pair<String, String>>()
        var timedOut = false
        lifecycle.lock()
        val activeRun = try {
            val lockedRun = currentRun ?: return
            if (lockedRun.state == LiveHttpRunState.OPEN) {
                lockedRun.state = LiveHttpRunState.CLOSING
                signalWakeupLocked(lockedRun)?.let { deferredDiagnostics += "wakeup" to it }
            }
            for (client in lockedRun.activeClients) {
                if (shutdown(client.descriptor, SHUT_RDWR) != 0 && errno != ENOTCONN) {
                    deferredDiagnostics += "shutdown" to
                        "live UI client shutdown failed: ${systemErrorText()}"
                }
            }

            val startedAt = TimeSource.Monotonic.markNow()
            while (currentRun === lockedRun && lockedRun.state != LiveHttpRunState.DRAINED) {
                val remaining = STOP_DRAIN_TIMEOUT_NANOSECONDS -
                    startedAt.elapsedNow().inWholeNanoseconds.coerceAtLeast(0L)
                if (remaining <= 0L) {
                    timedOut = true
                    break
                }
                lifecycle.waitForNanoseconds(minOf(remaining, CONDITION_WAIT_SLICE_NANOSECONDS).toULong())
            }
            lockedRun
        } finally {
            lifecycle.unlock()
        }

        for ((key, message) in deferredDiagnostics) reportRunErrorOnce(activeRun, key, message)
        if (timedOut) {
            val counts = runCounts(activeRun)
            reportRunErrorOnce(
                activeRun,
                "drain-timeout",
                "live UI server stop timed out after 5 seconds " +
                    "(accept=${counts.accept}, active=${counts.active}, closing=${counts.closing})",
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun acceptLoop(run: LiveHttpRun) {
        try {
            while (true) {
                val events = pollRun(run) ?: break
                if (events.wakeEvents.hasAny(POLLIN, POLLERR, POLLHUP, POLLNVAL)) {
                    drainWakePipe(run)
                    if (isClosing(run)) break
                }
                if (events.listenerEvents.hasAny(POLLERR, POLLHUP, POLLNVAL)) {
                    reportRunErrorOnce(run, "listener-terminal", "live UI listener became unavailable")
                    beginClosingFromAccept(run)
                    break
                }
                if (events.listenerEvents.hasAny(POLLIN)) acceptReadyClients(run)
                if (isClosing(run)) break
            }
        } finally {
            beginClosingFromAccept(run)
            finishAcceptLoop(run)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun pollRun(run: LiveHttpRun): LiveHttpPollEvents? = memScoped {
        val descriptors = allocArray<pollfd>(2)
        descriptors[0].fd = run.listener
        descriptors[0].events = POLLIN.convert()
        descriptors[0].revents = 0
        descriptors[1].fd = run.wakeRead
        descriptors[1].events = POLLIN.convert()
        descriptors[1].revents = 0
        while (true) {
            val result = poll(descriptors, 2u, -1)
            if (result > 0) {
                return@memScoped LiveHttpPollEvents(
                    listenerEvents = descriptors[0].revents,
                    wakeEvents = descriptors[1].revents,
                )
            }
            if (result < 0 && errno == EINTR) continue
            reportRunErrorOnce(run, "poll", "live UI poll failed: ${systemErrorText()}")
            beginClosingFromAccept(run)
            return@memScoped null
        }
        error("unreachable")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun acceptReadyClients(run: LiveHttpRun) {
        while (!isClosing(run)) {
            val descriptor = accept(run.listener, null, null)
            if (descriptor >= 0) {
                registerClient(run, descriptor)
                continue
            }
            when (errno) {
                EINTR -> continue
                EAGAIN, EWOULDBLOCK -> return
                else -> {
                    reportRunErrorOnce(run, "accept", "live UI accept failed: ${systemErrorText()}")
                    usleep(ACCEPT_RETRY_MICROSECONDS)
                    return
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun registerClient(run: LiveHttpRun, descriptor: Int) {
        val setupFailure = try {
            configureSocket(descriptor, LiveHttpSocketKind.CLIENT)
        } catch (failure: Throwable) {
            close(descriptor)
            reportRunErrorOnce(
                run,
                "client-setup",
                "Unable to configure live UI client socket: " +
                    (failure.message ?: failure::class.simpleName),
            )
            return
        }
        if (setupFailure != null) {
            close(descriptor)
            reportRunErrorOnce(
                run,
                "client-setup",
                "Unable to configure live UI client socket: $setupFailure",
            )
            return
        }

        val client = LiveHttpClient(run.generation, descriptor)
        val registered = withLifecycleLock {
            if (
                currentRun === run &&
                run.state == LiveHttpRunState.OPEN &&
                run.activeClients.size < MAX_ACTIVE_CLIENTS
            ) {
                run.activeClients += client
                true
            } else {
                false
            }
        }
        if (!registered) {
            close(descriptor)
            return
        }
        dispatch_async(workerQueue) { serve(run, client) }
    }

    private fun serve(run: LiveHttpRun, client: LiveHttpClient) {
        try {
            val request = readRequest(client.descriptor)
            val response = request?.let { route(it, run.port) } ?: HttpResponse.badRequest()
            writeResponse(client.descriptor, response)
        } catch (failure: Throwable) {
            reportRunErrorOnce(
                run,
                "request",
                "live UI request failed: ${failure.message ?: failure::class.simpleName}",
            )
        } finally {
            finishClient(run, client)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun finishClient(run: LiveHttpRun, client: LiveHttpClient) {
        var removed = false
        lifecycle.lock()
        try {
            removed = run.activeClients.remove(client)
            if (removed) run.clientsClosing += 1
            lifecycle.broadcast()
        } finally {
            lifecycle.unlock()
        }
        close(client.descriptor)
        if (!removed) return
        lifecycle.lock()
        try {
            run.clientsClosing -= 1
            finishRunIfDrainedLocked(run)
            lifecycle.broadcast()
        } finally {
            lifecycle.unlock()
        }
    }

    private fun route(request: HttpRequest, port: Int): HttpResponse {
        val hosts = request.headers["host"].orEmpty()
        if (hosts.size != 1) return HttpResponse.badRequest()
        if (hosts.single() != "127.0.0.1:$port") return HttpResponse.misdirectedRequest()
        if (request.method != "GET") return HttpResponse.methodNotAllowed()
        if (!request.target.startsWith('/') || '#' in request.target) return HttpResponse.badRequest()

        val path = request.target.substringBefore('?')
        return when (path) {
            "/" -> HttpResponse.ok("text/html; charset=utf-8", page)
            "/api/live" -> routeLiveApi(request)
            else -> HttpResponse.notFound()
        }
    }

    private fun routeLiveApi(request: HttpRequest): HttpResponse {
        val authorization = request.headers["authorization"].orEmpty()
        val queryTokens = queryParameters(request.target, "token")
        val watchRequested = liveUiWatchRequested(request.target)
        val targetContainsWatch = queryParameters(request.target, "watch").any { it == "1" }
        val headerAuthorized = authorization.size == 1 &&
            secureEquals("Bearer $token", authorization.single()) &&
            queryTokens.isEmpty()
        val queryAuthorized = authorization.isEmpty() &&
            !targetContainsWatch &&
            queryTokens.size == 1 &&
            secureEquals(token, queryTokens.single())
        if (!headerAuthorized && !queryAuthorized) return HttpResponse.forbidden()

        if (watchRequested) onWatch()
        return HttpResponse.ok("application/json; charset=utf-8", state.currentJson())
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readRequest(client: Int): HttpRequest? = memScoped {
        val buffer = allocArray<ByteVar>(REQUEST_CHUNK_BYTES)
        val receiveTimeout = alloc<timeval>()
        val bytes = ArrayList<Byte>(REQUEST_CHUNK_BYTES)
        val startedAt = TimeSource.Monotonic.markNow()
        while (bytes.size < MAX_REQUEST_BYTES) {
            val remainingMicroseconds = REQUEST_DEADLINE_MICROSECONDS -
                startedAt.elapsedNow().inWholeMicroseconds.coerceAtLeast(0L)
            if (remainingMicroseconds <= 0L) return@memScoped null
            receiveTimeout.tv_sec = (remainingMicroseconds / MICROSECONDS_PER_SECOND).convert()
            receiveTimeout.tv_usec = (remainingMicroseconds % MICROSECONDS_PER_SECOND)
                .coerceAtLeast(1L)
                .convert()
            if (
                setsockopt(
                    client,
                    SOL_SOCKET,
                    SO_RCVTIMEO,
                    receiveTimeout.ptr,
                    sizeOf<timeval>().convert(),
                ) != 0
            ) {
                throw IllegalStateException("Unable to update live UI receive deadline: ${systemErrorText()}")
            }
            val capacity = minOf(REQUEST_CHUNK_BYTES, MAX_REQUEST_BYTES - bytes.size)
            val received = recv(client, buffer, capacity.convert(), 0).toInt()
            if (received < 0 && errno == EINTR) continue
            if (received <= 0) return@memScoped null
            for (byte in buffer.readBytes(received)) bytes.add(byte)
            val headerEnd = bytes.headerBoundaryEndIndex()
            if (headerEnd != null) {
                return@memScoped parseHttpRequest(ByteArray(headerEnd) { bytes[it] })
            }
        }
        null
    }

    private fun parseHttpRequest(bytes: ByteArray): HttpRequest? {
        val text = bytes.decodeToString()
        if ('\uFFFD' in text || '\u0000' in text) return null
        val header = text.substringBefore("\r\n\r\n", missingDelimiterValue = "")
        if (header.isEmpty()) return null
        val lines = header.split("\r\n")
        val requestParts = lines.first().split(' ')
        if (
            requestParts.size != 3 ||
            requestParts[0].isEmpty() ||
            requestParts[1].isEmpty() ||
            requestParts[2] !in HTTP_VERSIONS
        ) {
            return null
        }
        val headers = mutableMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator)
            if (!name.matches(HEADER_NAME_PATTERN)) return null
            val value = line.substring(separator + 1).trim(' ', '\t')
            if (value.any { it < ' ' && it != '\t' || it == '\u007f' }) return null
            headers.getOrPut(name.lowercase()) { mutableListOf() } += value
        }
        return HttpRequest(
            method = requestParts[0],
            target = requestParts[1],
            headers = headers,
        )
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
        if (sendAll(client, header)) sendAll(client, body)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun sendAll(client: Int, bytes: ByteArray): Boolean = bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val sent = send(
                client,
                pinned.addressOf(offset),
                (bytes.size - offset).convert(),
                MSG_NOSIGNAL,
            ).toInt()
            when {
                sent > 0 -> offset += sent
                sent < 0 && errno == EINTR -> continue
                else -> return@usePinned false
            }
        }
        true
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun openRunResources(): LiveHttpResources {
        val listener = openListener()
        val pipeEnds = try {
            openWakePipe()
        } catch (failure: Throwable) {
            close(listener)
            throw failure
        }
        return try {
            LiveHttpResources(
                listener = listener,
                wakeRead = pipeEnds.first,
                wakeWrite = pipeEnds.second,
                port = boundPort(listener),
            )
        } catch (failure: Throwable) {
            close(listener)
            close(pipeEnds.first)
            close(pipeEnds.second)
            throw failure
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun openListener(): Int = memScoped {
        val descriptor = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        if (descriptor < 0) throw IllegalStateException("Unable to create live UI socket: ${systemErrorText()}")
        try {
            configureSocket(descriptor, LiveHttpSocketKind.LISTENER)?.let {
                throw IllegalStateException("Unable to configure live UI socket: $it")
            }
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
    private fun openWakePipe(): Pair<Int, Int> = memScoped {
        val descriptors = allocArray<IntVar>(2)
        if (pipe(descriptors) != 0) {
            throw IllegalStateException("Unable to create live UI wake pipe: ${systemErrorText()}")
        }
        val readDescriptor = descriptors[0]
        val writeDescriptor = descriptors[1]
        try {
            configurePipeEnd(readDescriptor)?.let {
                throw IllegalStateException("Unable to configure live UI wake pipe: $it")
            }
            configurePipeEnd(writeDescriptor)?.let {
                throw IllegalStateException("Unable to configure live UI wake pipe: $it")
            }
            readDescriptor to writeDescriptor
        } catch (failure: Throwable) {
            close(readDescriptor)
            close(writeDescriptor)
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
    private fun drainWakePipe(run: LiveHttpRun) = memScoped {
        val buffer = allocArray<ByteVar>(WAKE_BUFFER_BYTES)
        while (true) {
            val received = read(run.wakeRead, buffer, WAKE_BUFFER_BYTES.convert()).toInt()
            when {
                received > 0 -> continue
                received < 0 && errno == EINTR -> continue
                received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) -> return@memScoped
                received == 0 -> return@memScoped
                else -> {
                    reportRunErrorOnce(run, "wakeup-read", "live UI wake pipe read failed: ${systemErrorText()}")
                    return@memScoped
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun signalWakeupLocked(run: LiveHttpRun): String? {
        if (!run.acceptDescriptorsAvailable) return null
        val signal = byteArrayOf(1)
        while (true) {
            val written = signal.usePinned { pinned ->
                write(run.wakeWrite, pinned.addressOf(0), 1uL).toInt()
            }
            when {
                written == 1 -> return null
                written < 0 && errno == EINTR -> continue
                written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) -> return null
                else -> return "live UI wake pipe write failed: ${systemErrorText()}"
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun beginClosingFromAccept(run: LiveHttpRun) {
        val diagnostics = mutableListOf<String>()
        lifecycle.lock()
        try {
            if (currentRun === run && run.state == LiveHttpRunState.OPEN) {
                run.state = LiveHttpRunState.CLOSING
                for (client in run.activeClients) {
                    if (shutdown(client.descriptor, SHUT_RDWR) != 0 && errno != ENOTCONN) {
                        diagnostics += "live UI client shutdown failed: ${systemErrorText()}"
                    }
                }
            }
        } finally {
            lifecycle.unlock()
        }
        for (message in diagnostics) reportRunErrorOnce(run, "shutdown", message)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun finishAcceptLoop(run: LiveHttpRun) {
        lifecycle.lock()
        try {
            run.acceptDescriptorsAvailable = false
            lifecycle.broadcast()
        } finally {
            lifecycle.unlock()
        }
        close(run.listener)
        close(run.wakeRead)
        close(run.wakeWrite)
        lifecycle.lock()
        try {
            run.acceptLoopFinished = true
            finishRunIfDrainedLocked(run)
            lifecycle.broadcast()
        } finally {
            lifecycle.unlock()
        }
    }

    private fun finishRunIfDrainedLocked(run: LiveHttpRun) {
        if (
            run.state == LiveHttpRunState.CLOSING &&
            run.acceptLoopFinished &&
            run.activeClients.isEmpty() &&
            run.clientsClosing == 0
        ) {
            run.state = LiveHttpRunState.DRAINED
            if (currentRun === run) currentRun = null
        }
    }

    private fun isClosing(run: LiveHttpRun): Boolean = withLifecycleLock {
        currentRun !== run || run.state != LiveHttpRunState.OPEN
    }

    private fun reportRunErrorOnce(run: LiveHttpRun, key: String, message: String) {
        val shouldReport = withLifecycleLock { run.reportedDiagnostics.add(key) }
        if (shouldReport) runCatching { logError(message) }
    }

    private fun runCounts(run: LiveHttpRun): LiveHttpRunCounts = withLifecycleLock {
        LiveHttpRunCounts(
            accept = if (run.acceptLoopFinished) 0 else 1,
            active = run.activeClients.size,
            closing = run.clientsClosing,
        )
    }

    private inline fun <T> withLifecycleLock(block: () -> T): T {
        lifecycle.lock()
        return try {
            block()
        } finally {
            lifecycle.unlock()
        }
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, List<String>>,
    )

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

            fun misdirectedRequest(): HttpResponse = text("421 Misdirected Request", "Misdirected request")

            fun notFound(): HttpResponse = text("404 Not Found", "Not found")

            fun methodNotAllowed(): HttpResponse = text("405 Method Not Allowed", "Method not allowed")

            private fun text(status: String, body: String): HttpResponse =
                HttpResponse(status, "text/plain; charset=utf-8", body)
        }
    }

    private companion object {
        val TOKEN_PATTERN = Regex("[0-9a-f]{64}")
        val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
        const val REQUEST_CHUNK_BYTES = 2_048
        const val MAX_REQUEST_BYTES = 16_384
        const val REQUEST_DEADLINE_MICROSECONDS = 2_000_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val STOP_DRAIN_TIMEOUT_NANOSECONDS = 5_000_000_000L
        const val CONDITION_WAIT_SLICE_NANOSECONDS = 100_000_000L
        const val ACCEPT_RETRY_MICROSECONDS = 100_000u
        const val MAX_ACTIVE_CLIENTS = 8
        const val WAKE_BUFFER_BYTES = 64
    }
}

fun liveUiWatchRequested(target: String): Boolean =
    target.substringBefore('?') == "/api/live" && queryParameters(target, "watch") == listOf("1")

private fun queryParameters(target: String, name: String): List<String> =
    target.substringAfter('?', "")
        .takeIf(String::isNotEmpty)
        ?.split('&')
        ?.mapNotNull { part ->
            part.substringBefore('=').takeIf { it == name }
                ?.let { part.substringAfter('=', "") }
        }
        .orEmpty()

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

@OptIn(ExperimentalForeignApi::class)
private fun setBooleanSocketOption(descriptor: Int, option: Int, enabled: IntVar): String? =
    if (
        setsockopt(
            descriptor,
            SOL_SOCKET,
            option,
            enabled.ptr,
            sizeOf<IntVar>().convert(),
        ) == 0
    ) {
        null
    } else {
        systemErrorText()
    }

@OptIn(ExperimentalForeignApi::class)
private fun configurePipeEnd(descriptor: Int): String? {
    val descriptorFlags = fcntl(descriptor, F_GETFD)
    if (descriptorFlags < 0) return "read descriptor flags: ${systemErrorText()}"
    if (fcntl(descriptor, F_SETFD, descriptorFlags or FD_CLOEXEC) != 0) {
        return "set FD_CLOEXEC: ${systemErrorText()}"
    }
    val statusFlags = fcntl(descriptor, F_GETFL)
    if (statusFlags < 0) return "read descriptor status flags: ${systemErrorText()}"
    if (fcntl(descriptor, F_SETFL, statusFlags or O_NONBLOCK) != 0) {
        return "set O_NONBLOCK: ${systemErrorText()}"
    }
    return null
}

private fun ArrayList<Byte>.headerBoundaryEndIndex(): Int? {
    if (size < 4) return null
    for (index in 3 until size) {
        if (
            this[index - 3] == '\r'.code.toByte() &&
            this[index - 2] == '\n'.code.toByte() &&
            this[index - 1] == '\r'.code.toByte() &&
            this[index] == '\n'.code.toByte()
        ) {
            return index + 1
        }
    }
    return null
}

private fun Short.hasAny(vararg events: Int): Boolean =
    events.any { (toInt() and it) != 0 }

private fun hostToNetwork32(value: UInt): UInt =
    ((value and 0x000000ffu) shl 24) or
        ((value and 0x0000ff00u) shl 8) or
        ((value and 0x00ff0000u) shr 8) or
        ((value and 0xff000000u) shr 24)

private fun networkToHost16(value: UShort): UShort =
    (((value.toUInt() and 0x00ffu) shl 8) or ((value.toUInt() and 0xff00u) shr 8)).toUShort()

private class LiveHttpCondition {
    private val lock = NSLock()
    private val wakeup = dispatch_semaphore_create(0)
    private var waiterCount = 0

    fun lock() {
        lock.lock()
    }

    fun unlock() {
        lock.unlock()
    }

    fun broadcast() {
        repeat(waiterCount) { dispatch_semaphore_signal(wakeup) }
    }

    fun waitForNanoseconds(timeoutNanoseconds: ULong) {
        waiterCount += 1
        lock.unlock()
        try {
            dispatch_semaphore_wait(
                wakeup,
                dispatch_time(DISPATCH_TIME_NOW, timeoutNanoseconds.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()),
            )
        } finally {
            lock.lock()
            waiterCount -= 1
        }
    }
}

private class LiveHttpRun(
    val generation: ULong,
    val listener: Int,
    val wakeRead: Int,
    val wakeWrite: Int,
    val port: Int,
) {
    var state = LiveHttpRunState.OPEN
    val activeClients = mutableSetOf<LiveHttpClient>()
    var clientsClosing = 0
    var acceptDescriptorsAvailable = true
    var acceptLoopFinished = false
    val reportedDiagnostics = mutableSetOf<String>()
}

private enum class LiveHttpRunState {
    OPEN,
    CLOSING,
    DRAINED,
}

private data class LiveHttpClient(val generation: ULong, val descriptor: Int)

private data class LiveHttpResources(
    val listener: Int,
    val wakeRead: Int,
    val wakeWrite: Int,
    val port: Int,
)

private data class LiveHttpPollEvents(val listenerEvents: Short, val wakeEvents: Short)

private data class LiveHttpRunCounts(val accept: Int, val active: Int, val closing: Int)

private const val REQUEST_TIMEOUT_SECONDS = 2L
