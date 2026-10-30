package com.lucifer.mobile.net.harness

import com.lucifer.mobile.net.EgressPolicyHosts
import com.lucifer.mobile.net.EgressTransport
import com.lucifer.mobile.net.HttpWire
import com.lucifer.mobile.net.ProxyEndpoint
import com.lucifer.mobile.net.Socks5
import com.lucifer.mobile.net.TorCheck
import com.lucifer.mobile.net.TransportPolicy
import com.lucifer.mobile.net.TransportRoute
import java.io.ByteArrayInputStream

/**
 * The Kotlin half of the transport spec.
 *
 * `tools/net-core/spec_check.py` asserts the same cases in Python so the rules can be executed without
 * a JVM. When both run (`scripts/verify_net_core.sh`) they must agree; where they disagree, this file
 * is the side that ships.
 *
 * No Android imports, no sockets, no network: everything here is pure logic, which is exactly why it
 * can run on a host at all.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private fun expectEq(name: String, actual: Any?, expected: Any?) {
    check("$name (got $actual, want $expected)", actual == expected)
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.hex() = joinToString(",") { (it.toInt() and 0xFF).toString() }

private fun caseSocks5() {
    expectEq("greeting without auth", Socks5.greeting(false).hex(), "5,1,0")
    expectEq("greeting with auth", Socks5.greeting(true).hex(), "5,2,0,2")
    expectEq("auth frame", Socks5.userPassAuth("u", "p").hex(), "1,1,117,1,112")

    // The point of the whole file: a name is sent as a name.
    val request = Socks5.connectRequest("check.torproject.org", 443)
    expectEq("CONNECT version", request[0], Socks5.VERSION)
    expectEq("CONNECT command", request[1], Socks5.CMD_CONNECT)
    expectEq("CONNECT reserved", request[2].toInt(), 0)
    expectEq("CONNECT address type is DOMAIN", request[3], Socks5.ATYP_DOMAIN)
    expectEq("CONNECT domain length", request[4].toInt(), "check.torproject.org".length)
    expectEq(
        "CONNECT carries the name, not an IP",
        String(request.copyOfRange(5, 5 + "check.torproject.org".length), Charsets.US_ASCII),
        "check.torproject.org"
    )
    expectEq("CONNECT port is big-endian", request[request.size - 2].toInt(), 0x01)
    expectEq("CONNECT port low byte", request[request.size - 1].toInt() and 0xFF, 0xBB)

    val onion = Socks5.connectRequest("abcd.onion", 80)
    expectEq("onion is still a domain", onion[3], Socks5.ATYP_DOMAIN)

    val literal = Socks5.connectRequest("93.184.216.34", 80)
    expectEq("IPv4 literal type", literal[3], Socks5.ATYP_IPV4)
    expectEq("IPv4 literal bytes", literal.copyOfRange(4, 8).hex(), "93,184,216,34")

    check("IPv4 recognition", Socks5.isIpv4Literal("1.2.3.4"))
    check("IPv4 rejects 999.1.1.1", !Socks5.isIpv4Literal("999.1.1.1"))
    check("IPv4 rejects a name", !Socks5.isIpv4Literal("example.com"))
    check("IPv6 recognition", Socks5.isIpv6Literal("2001:db8::1"))
    check("IPv6 rejects a name", !Socks5.isIpv6Literal("example.com"))

    expectEq("reply 0 succeeds", Socks5.parseReply(bytes(5, 0)).ok, true)
    expectEq("reply 5 fails", Socks5.parseReply(bytes(5, 5)).ok, false)
    expectEq(
        "reply 5 reason",
        Socks5.parseReply(bytes(5, 5)).reason,
        "connection refused by the destination"
    )
    expectEq("truncated reply fails", Socks5.parseReply(bytes(5)).ok, false)
    expectEq("wrong version fails", Socks5.parseReply(bytes(4, 0)).ok, false)
    check("unknown reply code names the number", "99" in Socks5.reason(99))
    expectEq("method choice parsed", Socks5.parseMethodChoice(bytes(5, 0)), Socks5.METHOD_NO_AUTH)
    expectEq("method choice rejects junk", Socks5.parseMethodChoice(bytes(4, 0)), null)
    check("auth success parsed", Socks5.parseAuthStatus(bytes(1, 0)))
    check("auth failure parsed", !Socks5.parseAuthStatus(bytes(1, 1)))

    // A host that cannot be encoded must throw rather than silently send something else.
    check("empty host is refused", runCatching { Socks5.connectRequest("", 80) }.isFailure)
    check("bad port is refused", runCatching { Socks5.connectRequest("a.com", 0) }.isFailure)
}

private fun casePolicy() {
    val direct = EgressTransport.DIRECT
    val tor = EgressTransport.TOR

    expectEq(
        "direct remote",
        TransportPolicy.decide("api.nvidia.com", direct, proxyHealthy = false).route,
        TransportRoute.DIRECT
    )
    expectEq(
        "tor remote tunnels",
        TransportPolicy.decide("api.nvidia.com", tor, proxyHealthy = true).route,
        TransportRoute.TUNNEL
    )

    // The assertion the feature rests on: no proxy, no request. Never a direct connection.
    val dead = TransportPolicy.decide("api.nvidia.com", tor, proxyHealthy = false)
    expectEq("tor without a proxy refuses", dead.route, TransportRoute.REFUSE)
    check("the refusal says there is no fallback", dead.reason.contains("no silent fallback"))

    expectEq(
        "loopback is not tunnelled",
        TransportPolicy.decide("127.0.0.1", tor, proxyHealthy = true).route,
        TransportRoute.DIRECT
    )
    expectEq(
        "LAN is not tunnelled",
        TransportPolicy.decide("192.168.1.10", tor, proxyHealthy = true).route,
        TransportRoute.DIRECT
    )
    check("172.16/12 is local", EgressPolicyHosts.isLocal("172.20.5.5"))
    check("172.32 is public", !EgressPolicyHosts.isLocal("172.32.5.5"))
    check("loopback helper", EgressPolicyHosts.isLoopback("127.0.0.5"))
    check("loopback helper rejects a public host", !EgressPolicyHosts.isLoopback("93.184.216.34"))

    expectEq(
        "onion in tor",
        TransportPolicy.decide("abc.onion", tor, proxyHealthy = true).route,
        TransportRoute.TUNNEL
    )
    expectEq(
        "onion on direct is refused",
        TransportPolicy.decide("abc.onion", direct, proxyHealthy = true).route,
        TransportRoute.REFUSE
    )
    expectEq(
        "onion with a dead proxy is refused",
        TransportPolicy.decide("abc.onion", tor, proxyHealthy = false).route,
        TransportRoute.REFUSE
    )
    check("onion detection", TransportPolicy.isOnion("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"))
    check("onion detection needs the suffix", !TransportPolicy.isOnion("onion.example.com"))

    expectEq(
        "offline refuses remote",
        TransportPolicy.decide("api.nvidia.com", tor, proxyHealthy = true, offline = true).route,
        TransportRoute.REFUSE
    )
    expectEq(
        "offline still allows loopback",
        TransportPolicy.decide("127.0.0.1", tor, proxyHealthy = true, offline = true).route,
        TransportRoute.DIRECT
    )

    expectEq("parse tor", EgressTransport.parse("TOR"), tor)
    expectEq("parse lowercase", EgressTransport.parse("tor"), tor)
    expectEq("parse junk is direct", EgressTransport.parse("socks5"), direct)
    expectEq("parse null is direct", EgressTransport.parse(null), direct)
    expectEq("storage round-trip", EgressTransport.parse(EgressTransport.storageValue(tor)), tor)

    expectEq("proxy default port", ProxyEndpoint.parse("127.0.0.1")?.port, 9050)
    expectEq("proxy explicit port", ProxyEndpoint.parse("127.0.0.1:9150")?.port, 9150)
    expectEq("proxy scheme stripped", ProxyEndpoint.parse("socks5h://10.0.0.2:9050")?.host, "10.0.0.2")
    expectEq("proxy junk is null", ProxyEndpoint.parse(""), null)
    expectEq("proxy bad port is null", ProxyEndpoint.parse("127.0.0.1:70000"), null)
    expectEq("orbot default", ProxyEndpoint.ORBOT.label, "127.0.0.1:9050")
}

private fun caseTorCheck() {
    expectEq(
        "in circuit",
        TorCheck.parse("""{"IsTor": true, "IP": "185.220.101.7"}"""),
        TorCheck.Verdict.InCircuit("185.220.101.7")
    )
    expectEq(
        "not in circuit",
        TorCheck.parse("""{"IsTor":false,"IP":"81.2.69.142"}"""),
        TorCheck.Verdict.NotInCircuit("81.2.69.142")
    )
    expectEq(
        "quoted boolean is accepted",
        TorCheck.parse("""{"IsTor":"true","IP":"1.2.3.4"}"""),
        TorCheck.Verdict.InCircuit("1.2.3.4")
    )
    check(
        "missing field is inconclusive",
        TorCheck.parse("""{"IP":"1.2.3.4"}""") is TorCheck.Verdict.Inconclusive
    )
    check("empty body is inconclusive", TorCheck.parse("   ") is TorCheck.Verdict.Inconclusive)

    val inCircuit = TorCheck.describe(TorCheck.Verdict.InCircuit("185.220.101.7"))
    check("in-circuit line names the exit", inCircuit.contains("185.220.101.7"))
    check("in-circuit line mentions DNS", inCircuit.contains("DNS"))
    val notInCircuit = TorCheck.describe(TorCheck.Verdict.NotInCircuit("81.2.69.142"))
    check("not-in-circuit is not dressed up as success", notInCircuit.startsWith("NOT IN CIRCUIT"))
    check("unverifiable does not claim a circuit", TorCheck.describe(TorCheck.Verdict.Inconclusive("x")).contains("do not assume"))
}

private fun caseHttpWire() {
    val get = HttpWire.encodeRequest(
        HttpWire.Request("GET", "example.com", "/a?b=1", mapOf("User-Agent" to "x")),
        https = true
    ).toString(Charsets.ISO_8859_1)
    check("request line", get.startsWith("GET /a?b=1 HTTP/1.1\r\n"))
    check("Host header added", get.contains("Host: example.com\r\n"))
    check("Connection: close added", get.contains("Connection: close\r\n"))
    check("GET carries no length", !get.contains("Content-Length"))
    check("request ends with a blank line", get.endsWith("\r\n\r\n"))

    val post = HttpWire.encodeRequest(
        HttpWire.Request("POST", "example.com", "/rpc", mapOf("Content-Type" to "application/json"),
            "{}".toByteArray()),
        https = true
    ).toString(Charsets.ISO_8859_1)
    check("POST declares its length", post.contains("Content-Length: 2\r\n"))
    check("POST body follows the headers", post.endsWith("{}"))

    val emptyPost = HttpWire.encodeRequest(
        HttpWire.Request("POST", "example.com", "/", emptyMap(), null), https = true
    ).toString(Charsets.ISO_8859_1)
    check("bodyless POST declares zero", emptyPost.contains("Content-Length: 0\r\n"))

    check(
        "header injection in the path is refused",
        runCatching {
            HttpWire.encodeRequest(
                HttpWire.Request("GET", "example.com", "/a\r\nX-Injected: 1"),
                https = true
            )
        }.isFailure
    )

    expectEq("status line", HttpWire.parseStatusLine("HTTP/1.1 200 OK"), 200 to "OK")
    expectEq("status without reason", HttpWire.parseStatusLine("HTTP/1.0 204"), 204 to "")
    expectEq("status junk", HttpWire.parseStatusLine("SSH-2.0-OpenSSH"), null)
    expectEq("status bad code", HttpWire.parseStatusLine("HTTP/1.1 999 Nope"), null)

    fun respond(raw: String, method: String = "GET", limit: Int = HttpWire.MAX_BODY_BYTES): HttpWire.Response? =
        HttpWire.readResponse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), method, limit)

    val simple = respond("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/plain\r\n\r\nhello")
    expectEq("content-length body", simple?.body?.toString(Charsets.ISO_8859_1), "hello")
    expectEq("status from a response", simple?.status, 200)
    expectEq("header lookup", simple?.header("content-type"), "text/plain")

    val chunked = respond(
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
    )
    expectEq("chunked body is joined", chunked?.body?.toString(Charsets.ISO_8859_1), "hello world")

    val closed = respond("HTTP/1.1 200 OK\r\n\r\nno length at all")
    expectEq("read-to-close body", closed?.body?.toString(Charsets.ISO_8859_1), "no length at all")

    expectEq("HEAD has no body", respond("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n", "HEAD")?.body?.size, 0)
    expectEq("204 has no body", respond("HTTP/1.1 204 No Content\r\nContent-Length: 10\r\n\r\n")?.body?.size, 0)

    val interim = respond("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi")
    expectEq("interim 100 is skipped", interim?.body?.toString(Charsets.ISO_8859_1), "hi")

    val huge = respond("HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n" + "x".repeat(5000), limit = 100)
    expectEq("body clamped to the ceiling", huge?.body?.size, 100)

    val chunkedClamped = respond(
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3e8\r\n" + "x".repeat(1000) + "\r\n0\r\n\r\n",
        limit = 64
    )
    expectEq("chunked clamped too", chunkedClamped?.body?.size, 64)

    expectEq("junk is not a response", HttpWire.parseStatusLine("hello"), null)
}

fun main() {
    caseSocks5()
    casePolicy()
    caseTorCheck()
    caseHttpWire()

    for (line in failures) println("FAIL  $line")
    println()
    if (failures.isNotEmpty()) {
        println("FAILED · $passed passed, ${failures.size} failed")
        kotlin.system.exitProcess(1)
    }
    println("PASS · $passed checks (network transport, Kotlin)")
}
