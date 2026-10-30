package com.lucifer.mobile.net

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Opening the socket, and nothing else.
 *
 * Two shapes only: a direct connection, or a SOCKS5 tunnel in which the **hostname** is handed to the
 * proxy. There is no third path and no fallback — [EgressHttp] decides which one a destination gets,
 * and this object has no opinion about policy. It throws on failure rather than quietly connecting
 * some other way.
 *
 * Kept out of `privacy/` because it has no Android imports: the handshake is exercised on the host by
 * `tools/net-core/` against a real loopback socket.
 */
object SocketDialer {

    /** TLS for a tunnelled socket, with hostname verification turned on explicitly. */
    fun tls(raw: Socket, host: String, port: Int, handshakeTimeoutMs: Int): SSLSocket {
        val factory = SSLContext.getDefault().socketFactory as SSLSocketFactory
        // `autoClose = true`: closing the TLS layer closes the tunnel under it, which is what stops a
        // failed handshake from leaving an open circuit behind.
        val socket = factory.createSocket(raw, host, port, true) as SSLSocket
        socket.useClientMode = true
        val parameters = socket.sslParameters
        // Without this, an intercepted certificate from any trusted CA would be accepted for any host.
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        socket.sslParameters = parameters
        socket.soTimeout = handshakeTimeoutMs.coerceIn(1_000, 120_000)
        socket.startHandshake()
        return socket
    }

    /**
     * Direct connection to [host]:[port].
     *
     * Note what does *not* happen here: no DNS is resolved before the caller has decided this request
     * may be direct. [EgressHttp] calls this only for DIRECT routes.
     */
    fun direct(host: String, port: Int, connectTimeoutMs: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), clamp(connectTimeoutMs))
        socket.soTimeout = clamp(connectTimeoutMs)
        return socket
    }

    /**
     * Tunnel to [host]:[port] through [endpoint].
     *
     * The host string is sent unresolved. For an IP literal the proxy gets the literal, because there
     * is nothing to resolve; for every name — including `.onion` — it gets the name.
     */
    fun tunnel(
        endpoint: ProxyEndpoint,
        host: String,
        port: Int,
        connectTimeoutMs: Int
    ): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), clamp(connectTimeoutMs))
            socket.soTimeout = clamp(connectTimeoutMs)

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(Socks5.greeting(endpoint.usesAuth))
            output.flush()
            val method = readFully(input, 2)
            val chosen = Socks5.parseMethodChoice(method)
                ?: throw IOException("the proxy did not answer the SOCKS5 greeting")
            when (chosen) {
                Socks5.METHOD_NO_AUTH -> Unit
                Socks5.METHOD_USER_PASS -> {
                    if (!endpoint.usesAuth) {
                        throw IOException("the proxy wants a username and password; none is configured")
                    }
                    output.write(Socks5.userPassAuth(endpoint.username, endpoint.password))
                    output.flush()
                    val auth = readFully(input, 2)
                    if (!Socks5.parseAuthStatus(auth)) {
                        throw IOException("the proxy rejected the SOCKS5 username/password")
                    }
                }
                Socks5.METHOD_NONE_ACCEPTABLE ->
                    throw IOException("the proxy accepts none of the offered SOCKS5 methods")
                else -> throw IOException("the proxy chose an unsupported SOCKS5 method ($chosen)")
            }

            output.write(Socks5.connectRequest(host, port))
            output.flush()

            // VER REP RSV ATYP, then the bound address (which we read and discard) and the port.
            val head = readFully(input, 4)
            val reply = Socks5.parseReply(head)
            if (!reply.ok) throw IOException("SOCKS5: ${reply.reason}")
            when (head[3]) {
                Socks5.ATYP_IPV4 -> readFully(input, 4 + 2)
                Socks5.ATYP_IPV6 -> readFully(input, 16 + 2)
                Socks5.ATYP_DOMAIN -> {
                    val length = readFully(input, 1)[0].toInt() and 0xFF
                    readFully(input, length + 2)
                }
                else -> throw IOException("the proxy replied with an unknown address type")
            }
            return socket
        } catch (error: Throwable) {
            // A tunnel that did not come up must not leave a socket (and a circuit) behind.
            try {
                socket.close()
            } catch (_: Exception) {
                // already closing
            }
            throw error
        }
    }

    /**
     * Is anything speaking SOCKS5 on this endpoint?
     *
     * A TCP connect plus the greeting — no CONNECT, no exit traffic. This is the cheap liveness check
     * the transport consults before refusing a request, and it lives here so that exactly one file in
     * the app constructs a socket.
     */
    fun probeSocks(endpoint: ProxyEndpoint, timeoutMs: Int = 3_000): Boolean = try {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), clamp(timeoutMs))
        socket.soTimeout = clamp(timeoutMs)
        socket.getOutputStream().run {
            write(Socks5.greeting(endpoint.usesAuth))
            flush()
        }
        val method = Socks5.parseMethodChoice(readFully(socket.getInputStream(), 2))
        socket.close()
        method != null && method != Socks5.METHOD_NONE_ACCEPTABLE
    } catch (_: Exception) {
        false
    }

    internal fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) throw IOException("the proxy closed the connection mid-handshake")
            read += n
        }
        return out
    }

    private fun clamp(value: Int): Int = value.coerceIn(500, 120_000)
}
