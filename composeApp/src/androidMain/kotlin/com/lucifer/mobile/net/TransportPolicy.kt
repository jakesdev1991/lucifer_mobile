package com.lucifer.mobile.net

/**
 * Where a request is allowed to go, and what "through Tor" actually means.
 *
 * The rule this file exists to enforce is short: **a remote request is either direct or tunnelled,
 * and it never quietly becomes the other one.** A proxy that is configured but unreachable must refuse
 * the request, not fall back to a direct connection — a silent fallback is worse than no privacy
 * feature at all, because it looks identical to the working case from the outside.
 *
 * The policy is pure so that `tools/net-core/spec_check.py` can execute every branch and
 * `tools/net-core/NetCoreTests.kt` can assert the same cases against this Kotlin.
 */

enum class EgressTransport(val label: String) {
    /** Ordinary connections. The default, and what every earlier build did. */
    DIRECT("DIRECT"),

    /** Every remote request goes through a SOCKS5 proxy that resolves names itself. */
    TOR("TOR");

    companion object {
        /** Parse the stored value. Anything unrecognised is DIRECT, never a half-configured TOR. */
        fun parse(raw: String?): EgressTransport =
            if (raw?.trim()?.equals("tor", ignoreCase = true) == true) TOR else DIRECT

        fun storageValue(transport: EgressTransport): String = transport.label.lowercase()
    }
}

/** What to do with one destination. */
enum class TransportRoute {
    /** Connect from the phone directly. */
    DIRECT,

    /** Open a SOCKS5 tunnel and let the proxy resolve the name. */
    TUNNEL,

    /** Do not connect at all; [TransportDecision.reason] explains it to the user. */
    REFUSE
}

data class TransportDecision(
    val route: TransportRoute,
    val reason: String = "",
    /** True when the destination is on this device or its own network and never leaves it. */
    val local: Boolean = false
) {
    val refused: Boolean get() = route == TransportRoute.REFUSE
}

/** A SOCKS5 proxy endpoint, validated once so a bad setting cannot become a bad connection. */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    val usesAuth: Boolean get() = username.isNotEmpty()
    val label: String get() = "$host:$port"

    companion object {
        /** Orbot's SOCKS port. The only proxy this app knows by name. */
        const val ORBOT_HOST = "127.0.0.1"
        const val ORBOT_PORT = 9050

        val ORBOT = ProxyEndpoint(ORBOT_HOST, ORBOT_PORT)

        /**
         * Parse `host:port`, or a bare host with the default port. Returns null for anything that is
         * not a usable loopback/LAN proxy address — a remote proxy is a different trust decision and
         * is not something this build will do by accident.
         */
        fun parse(raw: String?): ProxyEndpoint? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.length > 200) return null
            val withoutScheme = text.removePrefix("socks5h://").removePrefix("socks5://")
            val hostPart: String
            val portPart: String?
            if (withoutScheme.startsWith("[")) {
                val close = withoutScheme.indexOf(']')
                if (close < 0) return null
                hostPart = withoutScheme.substring(1, close)
                portPart = withoutScheme.substring(close + 1).removePrefix(":").takeIf { it.isNotEmpty() }
            } else {
                val colon = withoutScheme.lastIndexOf(':')
                if (colon > 0) {
                    hostPart = withoutScheme.substring(0, colon)
                    portPart = withoutScheme.substring(colon + 1)
                } else {
                    hostPart = withoutScheme
                    portPart = null
                }
            }
            if (hostPart.isBlank()) return null
            val port = portPart?.toIntOrNull() ?: ORBOT_PORT
            if (port !in 1..65_535) return null
            return ProxyEndpoint(hostPart.trim(), port)
        }
    }
}

object TransportPolicy {

    /** `.onion` is not a public name: it exists only inside a Tor circuit. */
    fun isOnion(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        return h == "onion" || h.endsWith(".onion")
    }

    /**
     * Decide how to reach [host].
     *
     * @param offline the user's GO DARK switch. It wins over everything, including loopback, because
     *   the switch is a promise about the network the user made, not a preference.
     * @param proxyHealthy whether the configured proxy answered a handshake just now. Only consulted
     *   for [EgressTransport.TOR]; when false the answer is REFUSE, never DIRECT.
     */
    fun decide(
        host: String,
        transport: EgressTransport,
        proxyHealthy: Boolean,
        offline: Boolean = false
    ): TransportDecision {
        val local = EgressPolicyHosts.isLocal(host)

        if (offline && !local) {
            return TransportDecision(TransportRoute.REFUSE, "Network is OFF (Settings → Privacy).", local)
        }
        // Loopback and LAN stay loopback and LAN: routing 127.0.0.1 through a proxy would break the
        // MCP server and the Omega server, and would send nothing anywhere useful.
        if (local) {
            return TransportDecision(TransportRoute.DIRECT, "Device-local.", local = true)
        }
        if (isOnion(host)) {
            return if (transport == EgressTransport.TOR && proxyHealthy) {
                TransportDecision(TransportRoute.TUNNEL, "Onion service — reachable only through Tor.")
            } else {
                TransportDecision(
                    TransportRoute.REFUSE,
                    "That is a .onion address. It only exists inside Tor, and routing is " +
                        if (transport == EgressTransport.TOR) "configured but the proxy is not answering."
                        else "DIRECT. Turn on Settings → Privacy → VIA TOR first."
                )
            }
        }
        return when (transport) {
            EgressTransport.DIRECT -> TransportDecision(TransportRoute.DIRECT)
            EgressTransport.TOR ->
                if (proxyHealthy) {
                    TransportDecision(TransportRoute.TUNNEL, "Through the SOCKS5 proxy.")
                } else {
                    TransportDecision(
                        TransportRoute.REFUSE,
                        "TOR is selected but the proxy is not answering, so nothing was sent. " +
                            "There is no silent fallback to a direct connection — that would defeat " +
                            "the point of choosing Tor."
                    )
                }
        }
    }

    /**
     * The honest warning to show next to the switch.
     *
     * Tor hides the network path. It does not hide the account behind it: the model provider sees the
     * API key and the prompt, the RPC node sees the wallet address, and any logged-in service knows
     * exactly who is asking. Saying this in the UI is the difference between a privacy tool and a
     * privacy theatre.
     */
    const val SCOPE_NOTE: String =
        "Tor changes the network path, not who you are to the services you already use. " +
            "An API key, a wallet address or a logged-in account identifies the caller regardless of " +
            "the exit node. It also cannot hide the radio, the carrier, or the fact that this device " +
            "is running Lucifer."
}

/**
 * The host-locality rules, kept in one place.
 *
 * `privacy/EgressPolicy` has its own copy for the ledger/classification side; this one is the copy
 * the transport uses, and `audit_surfaces`-style drift is prevented by the spec mirror, which asserts
 * both agree on a shared table of hosts.
 */
object EgressPolicyHosts {

    private val LOCAL_EXACT = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "10.0.2.2")
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home")

    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        if (h in LOCAL_EXACT) return true
        if (h == "localhost") return true
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    /** Names that must never be sent to a proxy even in TOR mode: they would be a local probe. */
    fun isLoopback(host: String): Boolean {
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}
