package com.lucifer.mobile.privacy

import android.content.Context
import android.util.Log
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.net.EgressTransport
import com.lucifer.mobile.net.ProxyEndpoint
import com.lucifer.mobile.net.TorCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tor, as this app can honestly provide it: a SOCKS5 tunnel for Lucifer's *own* traffic.
 *
 * There is no bundled Tor binary and no attempt to hide one. Orbot is the client — it is the
 * maintained Android build of Tor, it can be audited by people who are not me, and it already solves
 * the hard parts (bootstrapping, bridges, pluggable transports, a circuit that survives the radio
 * changing). This object owns three things only: the mode, the proxy address, and the evidence that
 * the proxy is really Tor.
 *
 * ## Why not a VpnService?
 *
 * A `VpnService` intercepts IP packets. To turn those into Tor circuits you need a userspace TCP/IP
 * stack (handshake state, sequence numbers, retransmission, MSS clamping, UDP assoc), a UDP path that
 * either drops or converts to SOCKS5 UDP-associate, and `protect()` on every socket that must escape
 * the tunnel or the app loops into itself. That is a project measured in months, and a half-finished
 * one is worse than nothing: it looks like a VPN, reports connected, and leaks.
 *
 * So Lucifer does the honest half. His own requests — prompts, page fetches, searches, RPC, code
 * downloads — go through Tor when this is on. Device-wide tunnelling stays where it belongs: Orbot's
 * own VPN mode, or WireGuard. § `vpnPresent` and the panel report which of those is actually running,
 * because "some VPN is up" is a claim worth verifying rather than assuming.
 */
object TorRuntime {

    private const val TAG = "TorRuntime"

    /** Orbot's package. Named so the panel can tell "installed" from "not installed". */
    private const val ORBOT_PACKAGE = "org.torproject.android"

    /** How long a proxy liveness answer is trusted before it is probed again. */
    private const val PROBE_TTL_MS = 20_000L

    /** A TCP + greeting probe, not a full circuit: this runs on the request path. */
    private const val PROBE_TIMEOUT_MS = 3_000

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedTransport: EgressTransport? = null
    @Volatile private var cachedProxy: ProxyEndpoint? = null
    @Volatile private var healthy: Boolean? = null
    @Volatile private var healthyAt: Long = 0L
    @Volatile private var lastCircuit: String = TorCheck.UNCHECKED

    val context: Context? get() = appContext

    fun install(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ── mode ─────────────────────────────────────────────────────────

    fun transport(): EgressTransport {
        cachedTransport?.let { return it }
        val fromDisk = EgressTransport.parse(
            appContext?.let { AppConfig.readText(it, AppConfig.EGRESS_TRANSPORT) }
        )
        cachedTransport = fromDisk
        return fromDisk
    }

    fun setTransport(context: Context, transport: EgressTransport) {
        cachedTransport = transport
        // Changing transport invalidates every cached answer about the old one.
        healthy = null
        healthyAt = 0L
        lastCircuit = TorCheck.UNCHECKED
        AppConfig.write(context, AppConfig.EGRESS_TRANSPORT, EgressTransport.storageValue(transport))
        Log.i(TAG, "transport set to ${transport.label}")
    }

    // ── proxy ────────────────────────────────────────────────────────

    /** The configured proxy, defaulting to Orbot's loopback SOCKS port. */
    fun proxy(): ProxyEndpoint {
        cachedProxy?.let { return it }
        val configured = appContext?.let { AppConfig.readText(it, AppConfig.EGRESS_PROXY) }
        val endpoint = ProxyEndpoint.parse(configured) ?: ProxyEndpoint.ORBOT
        cachedProxy = endpoint
        return endpoint
    }

    fun setProxy(context: Context, raw: String): Boolean {
        val parsed = ProxyEndpoint.parse(raw) ?: return false
        cachedProxy = parsed
        healthy = null
        healthyAt = 0L
        AppConfig.write(context, AppConfig.EGRESS_PROXY, parsed.label)
        return true
    }

    /**
     * Is something answering on the proxy port?
     *
     * Deliberately cheap and cached: a TCP connect plus a SOCKS5 greeting, no CONNECT, no exit
     * traffic. It answers "would a request fail immediately", not "am I anonymous" — that is
     * [testCircuit], which costs a real request and is therefore never run automatically.
     */
    fun proxyReachable(): Boolean {
        val now = System.currentTimeMillis()
        healthy?.let { if (now - healthyAt < PROBE_TTL_MS) return it }
        val result = probeProxy(proxy())
        healthy = result
        healthyAt = now
        if (!result) Log.i(TAG, "no SOCKS5 proxy answering at ${proxy().label}")
        return result
    }

    private fun probeProxy(endpoint: ProxyEndpoint): Boolean =
        com.lucifer.mobile.net.SocketDialer.probeSocks(endpoint, PROBE_TIMEOUT_MS)

    // ── evidence ─────────────────────────────────────────────────────

    /**
     * Ask Tor Project whether this traffic is in a circuit.
     *
     * Runs through [EgressHttp.exchange], so it takes the same tunnel a real request would — a probe
     * that bypassed the tunnel would prove nothing. It appears in the egress ledger like any other
     * request, which is correct: it *is* one.
     */
    suspend fun testCircuit(context: Context): String = withContext(Dispatchers.IO) {
        if (transport() != EgressTransport.TOR) {
            lastCircuit = "NOT CHECKED · transport is DIRECT"
            return@withContext lastCircuit
        }
        val reply = EgressHttp.exchange(
            EgressHttp.Request(
                url = TorCheck.ENDPOINT,
                userInitiated = true,
                expected = EgressClass.PAGE,
                connectTimeoutMs = TorCheck.TIMEOUT_MS,
                readTimeoutMs = TorCheck.TIMEOUT_MS,
                maxBodyBytes = 8_192
            )
        )
        if (reply.status == 0) {
            healthy = false
            healthyAt = System.currentTimeMillis()
            lastCircuit = TorCheck.describe(TorCheck.Verdict.Unreachable(reply.error.ifBlank { "no answer" }))
            return@withContext lastCircuit
        }
        val verdict = if (reply.ok) {
            TorCheck.parse(reply.text())
        } else {
            TorCheck.Verdict.Inconclusive("the check endpoint answered ${reply.status}")
        }
        healthy = verdict is TorCheck.Verdict.InCircuit
        healthyAt = System.currentTimeMillis()
        lastCircuit = TorCheck.describe(verdict)
        Log.i(TAG, lastCircuit)
        lastCircuit
    }

    fun circuitStatus(): String = lastCircuit

    // ── what the panel draws ─────────────────────────────────────────

    fun orbotInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0) != null
    } catch (_: Exception) {
        false
    }

    /** True when the OS reports an app-provided VPN transport. Read-only, like every other probe. */
    fun vpnPresent(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val network = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    } catch (_: Exception) {
        false
    }

    /**
     * The state line for Settings, assembled from facts rather than hopes.
     */
    fun status(context: Context): String {
        val mode = transport()
        val endpoint = proxy()
        val reachable = proxyReachable()
        val orbot = if (orbotInstalled(context)) "Orbot installed" else "Orbot not installed"
        val vpn = if (vpnPresent(context)) "system VPN present" else "no system VPN"
        return when (mode) {
            EgressTransport.DIRECT ->
                "DIRECT · $orbot · $vpn · requests leave from this device's own address"
            EgressTransport.TOR ->
                "TOR · ${endpoint.label} ${if (reachable) "ANSWERING" else "NOT ANSWERING"} · " +
                    "$orbot · $vpn · $lastCircuit"
        }
    }

    /** Test seam: forget cached state so a host test can re-read it. */
    fun invalidate() {
        cachedTransport = null
        cachedProxy = null
        healthy = null
        healthyAt = 0L
        lastCircuit = TorCheck.UNCHECKED
    }

    /** The paragraph shown under the switch. Kept here so the UI and the docs cannot drift. */
    val SCOPE_NOTE: String
        get() = com.lucifer.mobile.net.TransportPolicy.SCOPE_NOTE
}
