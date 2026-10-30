package com.lucifer.mobile.privacy

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.zip.GZIPInputStream
import com.lucifer.mobile.net.EgressTransport
import com.lucifer.mobile.net.HttpWire
import com.lucifer.mobile.net.SocketDialer
import com.lucifer.mobile.net.TransportPolicy
import com.lucifer.mobile.net.TransportRoute
import java.io.ByteArrayInputStream

/**
 * THE ONLY WAY OUT.
 *
 * [NetworkGuard] used to be a convention: every call site was expected to ask it before calling
 * `openConnection()`, and an audit script failed the build if one forgot. That works, but it means the
 * guard is only as good as the next person's memory — and an audit can only see the call sites it
 * knows how to look for.
 *
 * This object inverts that. Every socket goes through one of two doors:
 *
 *  - [exchange] for anything remote. It authorises, then decides the route (direct or a SOCKS5
 *    tunnel), then speaks HTTP over the socket it opened itself.
 *  - [open] for loopback and LAN only, where an `HttpURLConnection` is the right tool and the traffic
 *    never leaves the device or its own network.
 *
 * Application code no longer sees `openConnection()` at all, and
 * `tools/privacy-core/audit_egress.py --check` fails if it reappears outside this file. When the
 * transport is TOR, [open] refuses a remote destination instead of quietly connecting directly —
 * there is no path from "Tor selected" to "sent in the clear".
 */
object EgressHttp {

    private const val TAG = "EgressHttp"

    /** Defaults used when a caller has no opinion. Both are clamped, never zero, never infinite. */
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_READ_TIMEOUT_MS = 20_000

    // ── the local-only door ──────────────────────────────────────────

    /**
     * The outcome of an authorisation: either a connection you may use, or the decision that refused
     * it. Never both. [connection] is null exactly when [decision].blocked is true.
     */
    data class Grant(
        val decision: EgressDecision,
        val connection: HttpURLConnection?
    ) {
        val blocked: Boolean get() = connection == null

        /** The honest line to return instead of pretending the call happened. */
        fun refusal(what: String): String = NetworkGuard.refusalMessage(decision, what)
    }

    /**
     * Authorise [url] and open a connection to it — **for a device-local destination only**.
     *
     * The four remaining callers are the loopback MCP servers, which is exactly what this door is for.
     * A remote URL here is refused rather than handed back, because it would bypass the transport
     * decision in [exchange] and, in TOR mode, connect straight out.
     */
    fun open(
        url: String,
        userInitiated: Boolean,
        expected: EgressClass? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        followRedirects: Boolean = false
    ): Grant {
        val decision = NetworkGuard.authorize(url, userInitiated, expected)
        if (decision.blocked) return Grant(decision, null)
        if (decision.kind != EgressClass.LOCAL && TorRuntime.transport() != EgressTransport.DIRECT) {
            Log.i(TAG, "refused ${decision.host}: open() is local-only while transport is ${TorRuntime.transport()}")
            return Grant(decision.copy(outcome = EgressOutcome.BLOCKED_POLICY, allowed = false), null)
        }
        return Grant(decision, connect(URL(url), connectTimeoutMs, readTimeoutMs, followRedirects))
    }

    /** Authorisation only, for callers that need a yes/no without a connection (probes, previews). */
    fun allowed(url: String, userInitiated: Boolean): Boolean =
        !NetworkGuard.authorize(url, userInitiated).blocked

    private fun connect(
        url: URL,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean
    ): HttpURLConnection? = try {
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = clampTimeout(connectTimeoutMs)
            readTimeout = clampTimeout(readTimeoutMs)
            // Never follow a redirect by default: a 302 would carry credentials or a payload to a host
            // this phone never chose. Callers that follow redirects do it one hop at a time, so every
            // new destination is authorised and recorded.
            instanceFollowRedirects = followRedirects
        }
    } catch (error: Exception) {
        // A malformed URL is a policy outcome, not a crash: report it as refused.
        Log.w(TAG, "could not open ${url.take(64)}: ${error.javaClass.simpleName}")
        null
    }

    // ── the remote door ──────────────────────────────────────────────

    data class Request(
        val url: String,
        val userInitiated: Boolean,
        val expected: EgressClass? = null,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
        val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        val maxBodyBytes: Int = HttpWire.MAX_BODY_BYTES,
        /**
         * A decision the caller already holds. A model turn authorises once and retries across
         * models; each retry still opens a real socket, and this keeps the ledger at one line per
         * turn while the transport decision is still made fresh per attempt.
         */
        val decision: EgressDecision? = null
    )

    data class Reply(
        val decision: EgressDecision,
        val route: TransportRoute,
        val transport: String,
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        val error: String = ""
    ) {
        val ok: Boolean get() = status in 200..299

        fun text(): String {
            val charsetName = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second
                ?.let { Regex("charset=([A-Za-z0-9_.:\\-]+)").find(it)?.groupValues?.get(1) }
                ?: "UTF-8"
            val charset = try {
                java.nio.charset.Charset.forName(charsetName)
            } catch (_: Exception) {
                Charsets.UTF_8
            }
            return String(body, charset)
        }

        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, true) }?.second

        fun headerValues(name: String): List<String> =
            headers.filter { it.first.equals(name, true) }.map { it.second }

        /** The line to return, or show, when there is no answer. */
        fun refusal(what: String): String =
            if (decision.blocked) NetworkGuard.refusalMessage(decision, what)
            else "[BLOCKED] $what: $error"
    }

    /**
     * Send one request and read one response.
     *
     * Failures are returned, never thrown: every caller in this app turns a dead endpoint into an
     * honest sentence, and an exception here would turn "the network is down" into a crash.
     */
    fun exchange(request: Request): Reply {
        val decision = request.decision ?: NetworkGuard.authorize(
            request.url, request.userInitiated, request.expected
        )
        if (decision.blocked) {
            return Reply(decision, TransportRoute.REFUSE, "NONE", 0, error = decision.reason)
        }

        val uri = try {
            java.net.URI(request.url)
        } catch (error: Exception) {
            return Reply(decision, TransportRoute.REFUSE, "NONE", 0, error = "malformed URL")
        }
        val scheme = uri.scheme?.lowercase() ?: "https"
        val host = uri.host?.lowercase()
            ?: return Reply(decision, TransportRoute.REFUSE, "NONE", 0, error = "URL has no host")
        val https = scheme == "https"
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val transport = TorRuntime.transport()
        // Only probe the proxy when the answer could matter: a loopback request never tunnels, and a
        // probe on that path would add seconds to something that never left the device.
        val localDestination = com.lucifer.mobile.net.EgressPolicyHosts.isLocal(host)
        val route = TransportPolicy.decide(
            host = host,
            transport = transport,
            proxyHealthy = transport == EgressTransport.DIRECT || localDestination ||
                TorRuntime.proxyReachable(),
            offline = TorRuntime.context?.let { !NetworkGuard.isOnline(it) } ?: false
        )
        if (route.refused) {
            Log.i(TAG, "refused ${decision.kind.label} to $host: ${route.reason}")
            return Reply(
                decision.copy(outcome = EgressOutcome.BLOCKED_POLICY, allowed = false),
                TransportRoute.REFUSE,
                transport.label,
                0,
                error = route.reason
            )
        }

        val label = if (route.route == TransportRoute.TUNNEL) "TOR" else "DIRECT"
        var socket: Socket? = null
        return try {
            val raw = if (route.route == TransportRoute.TUNNEL) {
                SocketDialer.tunnel(
                    TorRuntime.proxy(),
                    host,
                    port,
                    clampTimeout(request.connectTimeoutMs)
                )
            } else {
                SocketDialer.direct(host, port, clampTimeout(request.connectTimeoutMs))
            }
            socket = raw
            val connection: Socket = if (https) {
                SocketDialer.tls(raw, host, port, clampTimeout(request.connectTimeoutMs))
            } else {
                raw
            }
            connection.soTimeout = clampTimeout(request.readTimeoutMs)

            val hostHeader = if (port == if (https) 443 else 80) host else "$host:$port"
            val wireRequest = HttpWire.Request(
                method = request.method,
                host = hostHeader,
                path = path,
                headers = request.headers,
                body = request.body
            )
            connection.getOutputStream().run {
                write(HttpWire.encodeRequest(wireRequest, https))
                flush()
            }
            val response = HttpWire.readResponse(
                connection.getInputStream(),
                request.method,
                request.maxBodyBytes
            ) ?: return Reply(
                decision, route.route, label, 0,
                error = "the server closed before sending a complete response"
            )

            val body = if (response.header("Content-Encoding")?.contains("gzip", true) == true) {
                gunzip(response.body)
            } else {
                response.body
            }
            Reply(decision, route.route, label, response.status, response.headers, body)
        } catch (error: Exception) {
            val reason = when (error) {
                is java.net.SocketTimeoutException -> "timed out"
                is javax.net.ssl.SSLException -> "TLS refused: ${error.message ?: "handshake failed"}"
                else -> error.message ?: error.javaClass.simpleName
            }
            Log.w(TAG, "exchange to $host over $label failed: $reason")
            Reply(decision, route.route, label, 0, error = reason)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // already gone
            }
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val out = ByteArrayOutputStream(bytes.size.coerceAtLeast(64))
            val buffer = ByteArray(8_192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > HttpWire.MAX_BODY_BYTES) break
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        // A truncated gzip stream is still mostly text; hand back what arrived.
        bytes
    }

    private fun clampTimeout(value: Int): Int = value.coerceIn(500, 120_000)
}
