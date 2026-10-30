package com.lucifer.mobile.net

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * HTTP/1.1 written by hand, over whatever socket the dialer produced.
 *
 * `HttpURLConnection` cannot be used for the tunnel: it owns its own `Socket`, so there is no way to
 * hand it one that is already speaking to a SOCKS proxy with the hostname sent unresolved. The choice
 * is therefore a small client we can read, or no Tor at all.
 *
 * What is deliberately absent: redirects (every hop must be authorised and recorded separately),
 * connection reuse, HTTP/2, and automatic decompression. What is present: request encoding, a
 * response parser that handles Content-Length, `Transfer-Encoding: chunked` and read-to-close, a hard
 * byte ceiling, and header access for the callers that need it.
 *
 * Everything here works on streams, so the spec mirror can drive it with byte arrays and no network.
 */
object HttpWire {

    /** Absolute ceiling for a response body. A caller may ask for less; never for more. */
    const val MAX_BODY_BYTES = 8 * 1024 * 1024

    /** Ceiling for headers: a hostile server must not be able to grow one without bound. */
    const val MAX_HEADER_LINES = 200
    const val MAX_LINE_BYTES = 8 * 1024

    data class Request(
        val method: String,
        val host: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null
    )

    data class Response(
        val status: Int,
        val reason: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray
    ) {
        /** First value for a header, case-insensitive. */
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun headerValues(name: String): List<String> =
            headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

        fun text(): String {
            val charsetName = header("Content-Type")
                ?.let { Regex("charset=([A-Za-z0-9_.:\\-]+)").find(it)?.groupValues?.get(1) }
                ?: "UTF-8"
            val charset = try {
                java.nio.charset.Charset.forName(charsetName)
            } catch (_: Exception) {
                Charsets.UTF_8
            }
            return String(body, charset)
        }
    }

    /**
     * Origin-form request: `GET /path HTTP/1.1` with a mandatory `Host` header.
     *
     * `Connection: close` is always sent. Keep-alive would require pooling across a tunnel that may be
     * torn down at any moment, and this client issues one request per socket by design.
     */
    fun encodeRequest(request: Request, https: Boolean): ByteArray {
        val method = request.method.uppercase()
        require(method in setOf("GET", "POST", "PUT", "HEAD", "DELETE", "PATCH")) {
            "unsupported method $method"
        }
        val path = if (request.path.startsWith("/")) request.path else "/" + request.path
        require(!path.contains("\r") && !path.contains("\n")) { "header injection in request path" }

        val builder = StringBuilder()
        builder.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        val providedHost = request.headers.keys.any { it.equals("Host", ignoreCase = true) }
        if (!providedHost) builder.append("Host: ").append(request.host).append("\r\n")
        val providedClose = request.headers.keys.any { it.equals("Connection", ignoreCase = true) }
        if (!providedClose) builder.append("Connection: close\r\n")
        for ((name, value) in request.headers) {
            require(!name.contains(':') && !name.contains('\r') && !name.contains('\n')) { "bad header name" }
            require(!value.contains('\r') && !value.contains('\n')) { "header injection in $name" }
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        val body = request.body
        if (body != null) {
            val providedLength = request.headers.keys.any { it.equals("Content-Length", ignoreCase = true) }
            if (!providedLength) builder.append("Content-Length: ").append(body.size).append("\r\n")
        } else if (method == "POST" || method == "PUT" || method == "PATCH") {
            builder.append("Content-Length: 0\r\n")
        }
        builder.append("\r\n")
        val head = builder.toString().toByteArray(Charsets.ISO_8859_1)
        return if (body == null) head else head + body
    }

    /** `HTTP/1.1 200 OK` → 200. Null when the line is not a status line. */
    fun parseStatusLine(line: String): Pair<Int, String>? {
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size < 2) return null
        if (!parts[0].startsWith("HTTP/")) return null
        val status = parts[1].toIntOrNull() ?: return null
        if (status !in 100..599) return null
        return status to parts.getOrElse(2) { "" }
    }

    /**
     * Read one response. `HEAD` responses and 204/304 carry no body regardless of headers, which is
     * the one place a naive client hangs until the server gives up.
     */
    fun readResponse(
        input: InputStream,
        requestMethod: String,
        maxBodyBytes: Int = MAX_BODY_BYTES
    ): Response? {
        var line = readLine(input) ?: return null
        var status = parseStatusLine(line) ?: return null
        var headers = readHeaders(input) ?: return null

        // Interim 1xx responses (100-continue, 103 hints) precede the real one.
        var guard = 0
        while (status.first in 100..199 && status.first != 101 && guard++ < 5) {
            line = readLine(input) ?: return null
            status = parseStatusLine(line) ?: return null
            headers = readHeaders(input) ?: return null
        }

        val ceiling = maxBodyBytes.coerceIn(0, MAX_BODY_BYTES)
        val bodyless = requestMethod.uppercase() == "HEAD" || status.first == 204 || status.first == 304
        val body = when {
            bodyless -> ByteArray(0)
            else -> readBody(input, headers, ceiling)
        }
        return Response(status.first, status.second, headers, body)
    }

    fun readHeaders(input: InputStream): List<Pair<String, String>>? {
        val out = mutableListOf<Pair<String, String>>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: return if (out.isEmpty()) null else out
            if (line.isEmpty()) return out
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty()) continue
            out.add(name to value)
            if (++count > MAX_HEADER_LINES) return out
        }
    }

    private fun readBody(
        input: InputStream,
        headers: List<Pair<String, String>>,
        ceiling: Int
    ): ByteArray {
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        val chunked = header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
        if (chunked) return readChunked(input, ceiling)

        val length = header("Content-Length")?.trim()?.toLongOrNull()
        if (length != null && length >= 0) {
            val want = minOf(length, ceiling.toLong()).toInt()
            return readExactly(input, want)
        }
        // No length, no chunking: the body ends when the server closes. That is HTTP/1.0 behaviour and
        // some APIs still do it, so it is supported rather than treated as an error.
        return readToEnd(input, ceiling)
    }

    private fun readChunked(input: InputStream, ceiling: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < ceiling) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) {
                // Consume the trailer section so the socket is left in a sane state.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val take = minOf(size, ceiling - out.size())
            out.write(readExactly(input, take))
            if (take < size) break
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream(count.coerceAtLeast(0))
        val buffer = ByteArray(8_192)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readToEnd(input: InputStream, ceiling: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (out.size() < ceiling) {
            val read = input.read(buffer, 0, minOf(buffer.size, ceiling - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** CRLF- or LF-terminated line, as bytes so a binary body cannot be mistaken for text. */
    fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        var previous = -1
        while (true) {
            val next = input.read()
            if (next < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (next == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (previous == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.ISO_8859_1)
            }
            out.write(next)
            previous = next
            if (out.size() > MAX_LINE_BYTES) return out.toString("ISO-8859-1")
        }
    }
}
