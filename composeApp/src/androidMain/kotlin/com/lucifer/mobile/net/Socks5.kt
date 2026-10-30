package com.lucifer.mobile.net

/**
 * SOCKS5 (RFC 1928) + username/password auth (RFC 1929), as bytes rather than as a library.
 *
 * Why hand-rolled instead of `java.net.Proxy(Proxy.Type.SOCKS, …)`:
 *
 * The platform path hands `HttpURLConnection` an `InetSocketAddress`, which is **already resolved**
 * before the proxy ever sees it. That means the phone performs its own DNS lookup for every host it
 * meant to reach through Tor — the one leak that makes a Tor proxy worthless, because the resolver
 * sees every destination and the proxy merely relays a connection to an IP it was handed. There is no
 * way to prove from the outside that a given platform build uses `createUnresolved`, and "probably
 * fine" is not a standard for this.
 *
 * So the tunnel is ours: the hostname goes to the proxy as ATYP_DOMAIN (0x03) and the phone resolves
 * nothing. Every byte this object produces is asserted by `tools/net-core/spec_check.py`.
 *
 * Nothing here touches a socket. That is deliberate: the protocol is testable on a host, and the
 * dialer that does use it ([SocketDialer]) stays small enough to read in one sitting.
 */
object Socks5 {

    const val VERSION: Byte = 0x05
    const val VERSION_AUTH: Byte = 0x01

    const val METHOD_NO_AUTH: Byte = 0x00
    const val METHOD_USER_PASS: Byte = 0x02
    const val METHOD_NONE_ACCEPTABLE: Byte = 0xFF.toByte()

    const val CMD_CONNECT: Byte = 0x01

    const val ATYP_IPV4: Byte = 0x01
    const val ATYP_DOMAIN: Byte = 0x03
    const val ATYP_IPV6: Byte = 0x04

    /** Longest hostname sent to the proxy. RFC 1928 caps the field at 255; DNS caps a name at 253. */
    const val MAX_HOST_CHARS = 253

    const val MAX_CREDENTIAL_BYTES = 255

    /** `VER NMETHODS METHODS…` — what we can do, not what we want. */
    fun greeting(offerUserPass: Boolean): ByteArray {
        val methods = if (offerUserPass) {
            byteArrayOf(METHOD_NO_AUTH, METHOD_USER_PASS)
        } else {
            byteArrayOf(METHOD_NO_AUTH)
        }
        return byteArrayOf(VERSION, methods.size.toByte()) + methods
    }

    /** `VER ULEN UNAME PLEN PASSWD` — RFC 1929. Lengths are bytes, not characters. */
    fun userPassAuth(username: String, password: String): ByteArray {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        require(user.size in 1..MAX_CREDENTIAL_BYTES) { "SOCKS5 username must be 1..255 bytes" }
        require(pass.size in 1..MAX_CREDENTIAL_BYTES) { "SOCKS5 password must be 1..255 bytes" }
        return byteArrayOf(VERSION_AUTH, user.size.toByte()) + user +
            byteArrayOf(pass.size.toByte()) + pass
    }

    /**
     * `VER CMD RSV ATYP DST.ADDR DST.PORT`.
     *
     * A **name** is sent as a name. An IP literal is sent as an IP, because there is nothing to
     * resolve and pretending otherwise would waste the proxy's time. Everything else — including
     * `.onion` — goes out as ATYP_DOMAIN so the proxy resolves it, and the phone never does.
     */
    fun connectRequest(host: String, port: Int): ByteArray {
        require(port in 1..65_535) { "port out of range" }
        val trimmed = host.trim().removePrefix("[").removeSuffix("]")
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_HOST_CHARS) { "host out of range" }

        val address = when {
            isIpv4Literal(trimmed) -> byteArrayOf(ATYP_IPV4) + ipv4Bytes(trimmed)
            isIpv6Literal(trimmed) -> byteArrayOf(ATYP_IPV6) + ipv6Bytes(trimmed)
            else -> {
                val name = trimmed.toByteArray(Charsets.US_ASCII)
                require(name.size in 1..255) { "hostname too long" }
                byteArrayOf(ATYP_DOMAIN, name.size.toByte()) + name
            }
        }
        return byteArrayOf(VERSION, CMD_CONNECT, 0x00) + address + byteArrayOf(
            ((port shr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte()
        )
    }

    /** `VER METHOD` — the server's choice, or null if the reply is malformed. */
    fun parseMethodChoice(reply: ByteArray): Byte? {
        if (reply.size < 2) return null
        if (reply[0] != VERSION) return null
        return reply[1]
    }

    /** `VER STATUS` from the auth sub-negotiation. */
    fun parseAuthStatus(reply: ByteArray): Boolean =
        reply.size >= 2 && reply[0] == VERSION_AUTH && reply[1] == 0x00.toByte()

    data class Reply(val ok: Boolean, val code: Int, val reason: String)

    /**
     * `VER REP RSV ATYP BND.ADDR BND.PORT`.
     *
     * Only the reply code decides success. The bound address is ignored on purpose: some proxies
     * return `0.0.0.0:0`, and treating that as an error would reject working tunnels.
     */
    fun parseReply(reply: ByteArray): Reply {
        if (reply.size < 2) return Reply(false, -1, "the proxy closed before answering")
        if (reply[0] != VERSION) return Reply(false, -1, "not a SOCKS5 reply")
        val code = reply[1].toInt() and 0xFF
        return Reply(code == 0, code, reason(code))
    }

    /** Human-readable cause, so a refusal in the UI says which of these actually happened. */
    fun reason(code: Int): String = when (code) {
        0 -> "ok"
        1 -> "general proxy failure"
        2 -> "the proxy's ruleset denied the connection"
        3 -> "network unreachable from the proxy"
        4 -> "host unreachable from the proxy"
        5 -> "connection refused by the destination"
        6 -> "TTL expired"
        7 -> "command not supported by the proxy"
        8 -> "address type not supported by the proxy"
        else -> "unknown SOCKS5 failure ($code)"
    }

    // ── literal handling (shared with the policy, so both agree on what a literal is) ──

    fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    fun isIpv6Literal(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (!h.contains(':')) return false
        // Conservative: hex digits, colons, and at most one "::" or a trailing IPv4 tail.
        if (h.count { it == ':' } > 8) return false
        val group = h.substringBefore('%')
        for (character in group) {
            if (character != ':' && character != '.' && !character.isLetterOrDigit()) return false
            if (character.isLetterOrDigit() && character.lowercaseChar() !in 'a'..'f' &&
                !character.isDigit()
            ) return false
        }
        return true
    }

    private fun ipv4Bytes(host: String): ByteArray =
        host.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun ipv6Bytes(host: String): ByteArray {
        val stripped = host.substringBefore('%')
        val halves = stripped.split("::")
        val head = halves.getOrNull(0).orEmpty().split(':').filter { it.isNotEmpty() }
        val tail = if (halves.size > 1) halves[1].split(':').filter { it.isNotEmpty() } else emptyList()
        val groups = mutableListOf<Int>()
        for (part in head) {
            if (part.contains('.')) {
                val v4 = part.split('.').map { it.toInt() }
                groups.add(((v4[0] shl 8) or v4[1]) and 0xFFFF)
                groups.add(((v4[2] shl 8) or v4[3]) and 0xFFFF)
            } else {
                groups.add(part.toInt(16) and 0xFFFF)
            }
        }
        val tailGroups = tail.flatMap { part ->
            if (part.contains('.')) {
                val v4 = part.split('.').map { it.toInt() }
                listOf(((v4[0] shl 8) or v4[1]) and 0xFFFF, ((v4[2] shl 8) or v4[3]) and 0xFFFF)
            } else {
                listOf(part.toInt(16) and 0xFFFF)
            }
        }
        val filler = if (halves.size > 1) (8 - groups.size - tailGroups.size).coerceAtLeast(0) else 0
        val all = groups + List(filler) { 0 } + tailGroups
        val padded = (all + List((8 - all.size).coerceAtLeast(0)) { 0 }).take(8)
        val out = ByteArray(16)
        for (index in 0 until 8) {
            out[index * 2] = ((padded[index] shr 8) and 0xFF).toByte()
            out[index * 2 + 1] = (padded[index] and 0xFF).toByte()
        }
        return out
    }
}
