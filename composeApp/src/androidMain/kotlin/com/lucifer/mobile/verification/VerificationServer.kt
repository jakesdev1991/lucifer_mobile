package com.lucifer.mobile.verification

import com.lucifer.mobile.resilience.JsonLite
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transport under the MCP protocol: HTTP/1.1, one exchange per connection, bound to loopback.
 *
 * It is intentionally small and boring. The phone publishes nothing by itself — a Cloudflare tunnel or
 * an SSH reverse forward is what puts this socket on the internet, and that decision is made in the
 * World tab, not here. What this layer must do is refuse anything that is not a bounded JSON-RPC
 * request, and do it before the body is read into memory.
 *
 * Nothing in this file touches Android, so the harness starts a real socket and speaks to it.
 */
object HttpFraming {

    const val MAX_BODY = 400_000
    const val MAX_HEADER_LINE = 8_192
    const val MAX_HEADERS = 40
    const val MAX_AUTHORIZATION = 512

    data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
        val host: String?
    )

    data class Response(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8"
    )

    sealed interface Read {
        data class Ok(val request: Request) : Read
        data class Failed(val status: Int, val message: String) : Read
    }

    /** Reads one request. The body is capped here, before it is a String, so a 10 MB POST costs nothing. */
    fun read(input: InputStream): Read {
        val requestLine = readLine(input) ?: return Read.Failed(400, "Empty request.")
        val parts = requestLine.trim().split(' ')
        if (parts.size != 3) return Read.Failed(400, "Malformed request line.")
        val method = parts[0].uppercase()
        val path = parts[1]
        if (!parts[2].startsWith("HTTP/1.")) return Read.Failed(400, "Only HTTP/1.1 is supported.")
        if (path.length > 512) return Read.Failed(414, "Path is too long.")
        if (method != "POST" && method != "GET") return Read.Failed(405, "Only GET and POST are supported.")

        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: return Read.Failed(400, "Headers ended early.")
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) return Read.Failed(431, "Too many headers.")
            val colon = line.indexOf(':')
            if (colon <= 0) return Read.Failed(400, "Malformed header.")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty() || value.length > 4_096) return Read.Failed(400, "Malformed header.")
            headers[name] = value
        }

        val authorization = headers["authorization"]
        if (authorization != null && authorization.length > MAX_AUTHORIZATION) {
            return Read.Failed(400, "Authorization header is too long.")
        }
        if (headers.containsKey("transfer-encoding")) {
            return Read.Failed(411, "Chunked bodies are not accepted; send Content-Length.")
        }

        val declared = headers["content-length"]?.toIntOrNull()
        if (headers.containsKey("content-length") && declared == null) {
            return Read.Failed(400, "Content-Length is not a number.")
        }
        if (declared != null && declared > MAX_BODY) {
            return Read.Failed(413, "Request exceeds ${MAX_BODY / 1000} KB.")
        }
        val length = declared ?: 0
        if (method == "GET" && length > 0) return Read.Failed(400, "GET requests carry no body.")

        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) return Read.Failed(400, "Body ended early.")
            offset += read
        }
        val body = if (length == 0) "" else String(bytes, Charsets.UTF_8)

        return Read.Ok(
            Request(
                method = method,
                path = path,
                authorization = authorization,
                body = body,
                host = headers["host"]
            )
        )
    }

    fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        402 -> "Payment Required"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        421 -> "Misdirected Request"
        422 -> "Unprocessable Entity"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    fun errorJson(status: Int, message: String): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "id" to null,
            "error" to linkedMapOf<String, Any?>(
                "code" to if (status >= 500) McpProtocol.INTERNAL_ERROR else McpProtocol.INVALID_REQUEST,
                "message" to message
            )
        )
    )

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            if (builder.length >= MAX_HEADER_LINE) return null
            builder.append(byte.toChar())
        }
    }
}

/**
 * A loopback HTTP listener with a hard concurrency cap. Two at a time is deliberate: every request that
 * reaches the end of the pipeline blocks on remote elaboration, and a queue of slow Lean jobs is not
 * something a stranger should be able to build on Lucifer's phone.
 */
class LoopbackServer(
    private val host: String = "127.0.0.1",
    private val port: Int = ExposurePlan.DEFAULT_PORT,
    private val maxConcurrent: Int = 2,
    private val handler: (HttpFraming.Request) -> HttpFraming.Response
) {

    private var server: ServerSocket? = null
    private var acceptor: Thread? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private val active = AtomicInteger(0)

    val running: Boolean
        @Synchronized get() = server?.isClosed == false

    val boundPort: Int
        @Synchronized get() = server?.localPort ?: -1

    @Synchronized
    fun start(): Int {
        if (running) return boundPort
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(host), port), 8)
            }
        } catch (_: IOException) {
            return -1
        }
        server = socket
        pool = Executors.newFixedThreadPool(maxConcurrent) { runnable ->
            Thread(runnable, "lucifer-verifier-http").apply { isDaemon = true }
        }
        acceptor = Thread({ acceptLoop(socket) }, "lucifer-verifier-accept").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        pool?.shutdownNow()
        pool = null
        acceptor = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            if (active.get() >= maxConcurrent + 2) {
                client.close()
                continue
            }
            val pool = pool ?: run {
                client.close()
                return
            }
            try {
                pool.execute { serve(client) }
            } catch (_: Exception) {
                client.close()
            }
        }
    }

    private fun serve(client: Socket) {
        active.incrementAndGet()
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val response = when (val read = HttpFraming.read(client.getInputStream())) {
                is HttpFraming.Read.Failed -> HttpFraming.Response(
                    read.status,
                    HttpFraming.errorJson(read.status, read.message)
                )
                is HttpFraming.Read.Ok -> handler(read.request)
            }
            val body = response.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${HttpFraming.statusText(response.status)}\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val out = client.getOutputStream()
            out.write(head)
            out.write(body)
            out.flush()
        } catch (_: Exception) {
            // A client that hangs up mid-response is not an event worth logging, let alone retrying.
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
            active.decrementAndGet()
        }
    }
}

/**
 * The MCP endpoint Lucifer's world exposes: `/mcp` for JSON-RPC, `/health` for a liveness probe that
 * reveals nothing an unauthenticated stranger should not know.
 */
class VerifierEndpoint(
    private val runtime: VerifierRuntime,
    val port: Int = ExposurePlan.DEFAULT_PORT,
    private val host: String = "127.0.0.1"
) {

    private val startedAt = System.currentTimeMillis()
    private var server: LoopbackServer? = null

    val running: Boolean get() = server?.running == true
    val boundPort: Int get() = server?.boundPort ?: -1

    /** Returns the bound port, or -1 when the port is unavailable (another server already holds it). */
    fun start(): Int {
        if (running) return boundPort
        val listening = LoopbackServer(host = host, port = port) { request -> respond(request) }
        val bound = listening.start()
        if (bound <= 0) return -1
        server = listening
        return bound
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun respond(request: HttpFraming.Request): HttpFraming.Response {
        val path = request.path.substringBefore('?')
        return when {
            path == "/mcp" && request.method == "POST" -> {
                val handling = runtime.handle(request.body, request.authorization)
                HttpFraming.Response(handling.status, handling.body)
            }
            path == "/mcp" -> HttpFraming.Response(
                405,
                HttpFraming.errorJson(405, "Use POST for MCP JSON-RPC. GET is not a tool call.")
            )
            path == "/health" && request.method == "GET" -> HttpFraming.Response(200, health())
            path == "/" && request.method == "GET" -> HttpFraming.Response(200, health())
            else -> HttpFraming.Response(404, HttpFraming.errorJson(404, "No such endpoint."))
        }
    }

    fun health(): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "server" to McpProtocol.SERVER_NAME,
            "version" to "1.0.0",
            "protocolVersion" to McpProtocol.VERSION,
            "transport" to "http",
            "auth" to "bearer",
            "uptimeMs" to (System.currentTimeMillis() - startedAt),
            "port" to boundPort,
            "projects" to runtime.projects().map { it.id },
            "keys" to runtime.activeKeyCount,
            "tools" to VerifierTool.entries.map { it.wireName }
        )
    )
}

/**
 * Last look before a command reaches a shell.
 *
 * The planner already built this string, so this is not the boundary — it is a tripwire for a planner
 * regression. If someone later adds a tool that interpolates a path or a module name into a shell
 * word without quoting, these assertions fail loudly instead of shipping. A command that cannot
 * survive them is refused and nothing runs.
 */
object JobCommandGuard {

    const val MAX_COMMAND_LENGTH = 24_000

    fun check(command: String, job: LeanJobSpec): String? {
        if (command.isEmpty()) return "The planned command is empty."
        if (command.length > MAX_COMMAND_LENGTH) return "The planned command is too long."
        if (!command.startsWith("set -u")) return "The planned command does not open with `set -u`."
        if (!command.contains("D=$(mktemp -d /tmp/lucifer-verify.XXXXXX)")) {
            return "The planned command does not create an isolated temporary directory."
        }
        if (!command.contains("trap 'rm -rf \"\$D\"' EXIT")) {
            return "The planned command does not clean up its temporary directory."
        }
        if (!command.contains("LUCIFER_EXIT=")) return "The planned command cannot report an exit code."
        if (job.tool != VerifierTool.SORRY_SCAN && !command.contains("timeout ${job.timeoutSeconds} ")) {
            return "The planned command is not bounded by its ${job.timeoutSeconds}s timeout."
        }
        if (!LeanProject.validRoot(job.project.root)) return "The job carries an invalid project root."
        if (!command.contains("ROOT='" + job.project.root + "'")) {
            return "The planned command does not pin the configured project root."
        }
        if (!command.contains("cd \"\$ROOT\"")) return "The planned command does not enter a configured root."
        if ("\u0000" in command) return "The planned command contains a NUL byte."
        val source = job.source
        if (!source.isNullOrEmpty()) {
            val probe = source.trim().take(40)
            if (probe.length >= 8 && command.contains(probe)) {
                return "The planned command embeds raw source instead of a base64 payload."
            }
        }
        val module = job.module
        if (module != null && !LeanProject.validModule(module)) return "The planned command carries an invalid module."
        job.declarations.forEach { declaration ->
            if (!LeanProject.validDeclaration(declaration)) {
                return "The planned command carries an invalid declaration name."
            }
        }
        return null
    }
}
