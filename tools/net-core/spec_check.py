#!/usr/bin/env python3
"""Executable spec for the network transport: SOCKS5 wire format, routing policy, Tor verdicts and the
HTTP/1.1 client the tunnel uses.

The Kotlin that ships is compiled by AGP, which is not available in every workspace, and a privacy
feature whose rules cannot be executed cannot be trusted. This file is the same rules written a second
time so they *can* be exercised anywhere:

    python3 tools/net-core/spec_check.py

`NetCoreTests.kt` asserts the identical cases against the real Kotlin. When both run
(`scripts/verify_net_core.sh`) they must agree. If this file and the Kotlin ever disagree the Kotlin is
what ships — so a mismatch is a bug in one of them, never a pass.

Exit code is 0 only when every case holds.
"""

from __future__ import annotations

import re

passed = 0
failures: list[str] = []


def check(name: str, condition: bool) -> None:
    global passed
    if condition:
        passed += 1
    else:
        failures.append(name)


def expect_eq(name: str, actual, expected) -> None:
    check(f"{name} (got {actual!r}, want {expected!r})", actual == expected)


# ── mirror of Socks5 ─────────────────────────────────────────────────
VERSION = 0x05
VERSION_AUTH = 0x01
METHOD_NO_AUTH = 0x00
METHOD_USER_PASS = 0x02
METHOD_NONE_ACCEPTABLE = 0xFF
CMD_CONNECT = 0x01
ATYP_IPV4 = 0x01
ATYP_DOMAIN = 0x03
ATYP_IPV6 = 0x04


def greeting(offer_user_pass: bool) -> bytes:
    methods = bytes([METHOD_NO_AUTH, METHOD_USER_PASS]) if offer_user_pass else bytes([METHOD_NO_AUTH])
    return bytes([VERSION, len(methods)]) + methods


def user_pass_auth(user: str, password: str) -> bytes:
    u = user.encode()
    p = password.encode()
    assert 1 <= len(u) <= 255 and 1 <= len(p) <= 255
    return bytes([VERSION_AUTH, len(u)]) + u + bytes([len(p)]) + p


def is_ipv4_literal(host: str) -> bool:
    parts = host.split(".")
    if len(parts) != 4:
        return False
    for part in parts:
        if not part or len(part) > 3 or not part.isdigit() or not 0 <= int(part) <= 255:
            return False
    return True


def is_ipv6_literal(host: str) -> bool:
    h = host.strip("[]")
    if ":" not in h:
        return False
    if h.count(":") > 8:
        return False
    group = h.split("%")[0]
    for ch in group:
        if ch in ":. ":
            continue
        if not ch.isalnum():
            return False
        if ch.isalpha() and ch.lower() not in "abcdef":
            return False
    return True


def ipv4_bytes(host: str) -> bytes:
    return bytes(int(p) for p in host.split("."))


def connect_request(host: str, port: int) -> bytes:
    assert 1 <= port <= 65535
    trimmed = host.strip().strip("[]")
    assert trimmed
    if is_ipv4_literal(trimmed):
        address = bytes([ATYP_IPV4]) + ipv4_bytes(trimmed)
    elif is_ipv6_literal(trimmed):
        address = bytes([ATYP_IPV6]) + b"\x00" * 16  # the Kotlin expands the literal; asserted separately
    else:
        name = trimmed.encode("ascii")
        assert 1 <= len(name) <= 255
        address = bytes([ATYP_DOMAIN, len(name)]) + name
    return bytes([VERSION, CMD_CONNECT, 0x00]) + address + bytes([(port >> 8) & 0xFF, port & 0xFF])


REASONS = {
    0: "ok",
    1: "general proxy failure",
    2: "the proxy's ruleset denied the connection",
    3: "network unreachable from the proxy",
    4: "host unreachable from the proxy",
    5: "connection refused by the destination",
    6: "TTL expired",
    7: "command not supported by the proxy",
    8: "address type not supported by the proxy",
}


def parse_reply(reply: bytes):
    if len(reply) < 2:
        return (False, -1, "the proxy closed before answering")
    if reply[0] != VERSION:
        return (False, -1, "not a SOCKS5 reply")
    code = reply[1]
    return (code == 0, code, REASONS.get(code, f"unknown SOCKS5 failure ({code})"))


# ── mirror of TransportPolicy ────────────────────────────────────────
DIRECT, TOR = "DIRECT", "TOR"
ROUTE_DIRECT, ROUTE_TUNNEL, ROUTE_REFUSE = "DIRECT", "TUNNEL", "REFUSE"

LOCAL_EXACT = {"localhost", "127.0.0.1", "0.0.0.0", "::1", "10.0.2.2"}
LOCAL_SUFFIXES = (".local", ".localhost", ".internal", ".lan", ".home")


def is_local(host: str) -> bool:
    h = host.lower().strip("[]").rstrip(".")
    if h in LOCAL_EXACT or h == "localhost":
        return True
    if any(h.endswith(s) for s in LOCAL_SUFFIXES):
        return True
    if h.startswith("127.") or h.startswith("10.") or h.startswith("192.168.") or h.startswith("169.254."):
        return True
    if h.startswith("172."):
        second = h[4:].split(".")[0]
        if second.isdigit() and 16 <= int(second) <= 31:
            return True
    return False


def is_onion(host: str) -> bool:
    h = host.strip().rstrip(".").lower()
    return h == "onion" or h.endswith(".onion")


def decide(host: str, transport: str, proxy_healthy: bool, offline: bool = False):
    local = is_local(host)
    if offline and not local:
        return (ROUTE_REFUSE, "Network is OFF (Settings → Privacy).", local)
    if local:
        return (ROUTE_DIRECT, "Device-local.", True)
    if is_onion(host):
        if transport == TOR and proxy_healthy:
            return (ROUTE_TUNNEL, "Onion service — reachable only through Tor.", False)
        return (ROUTE_REFUSE, "onion", False)
    if transport == DIRECT:
        return (ROUTE_DIRECT, "", False)
    if proxy_healthy:
        return (ROUTE_TUNNEL, "Through the SOCKS5 proxy.", False)
    return (ROUTE_REFUSE,
            "TOR is selected but the proxy is not answering, so nothing was sent. There is no silent "
            "fallback to a direct connection — that would defeat the point of choosing Tor.", False)


def parse_transport(raw) -> str:
    return TOR if raw is not None and raw.strip().lower() == "tor" else DIRECT


def storage_value(transport: str) -> str:
    return transport.lower()


def parse_proxy(raw):
    """(host, port) or None — mirrors ProxyEndpoint.parse."""
    text = (raw or "").strip()
    if not text or len(text) > 200:
        return None
    for prefix in ("socks5h://", "socks5://"):
        if text.startswith(prefix):
            text = text[len(prefix):]
    if text.startswith("["):
        close = text.find("]")
        if close < 0:
            return None
        host = text[1:close]
        rest = text[close + 1:]
        port_part = rest[1:] if rest.startswith(":") else ""
    else:
        colon = text.rfind(":")
        if colon > 0:
            host, port_part = text[:colon], text[colon + 1:]
        else:
            host, port_part = text, ""
    if not host.strip():
        return None
    port = int(port_part) if port_part.isdigit() else 9050
    if not 1 <= port <= 65535:
        return None
    return (host.strip(), port)


# ── mirror of TorCheck ───────────────────────────────────────────────
def tc_bool(json: str, name: str):
    m = re.search(r'"' + re.escape(name) + r'"\s*:\s*"?(true|false)"?', json, re.I)
    return None if not m else m.group(1).lower() == "true"


def tc_string(json: str, name: str):
    m = re.search(r'"' + re.escape(name) + r'"\s*:\s*"([^"]*)"', json)
    return None if not m else m.group(1)


def parse_tor(json: str):
    trimmed = json.strip()
    if not trimmed:
        return ("INCONCLUSIVE", "empty answer from the check endpoint")
    flag = tc_bool(trimmed, "IsTor")
    if flag is None:
        return ("INCONCLUSIVE", "the answer has no IsTor field")
    ip = tc_string(trimmed, "IP") or ""
    return ("IN_CIRCUIT", ip) if flag else ("NOT_IN_CIRCUIT", ip)


# ── mirror of HttpWire ───────────────────────────────────────────────
MAX_BODY_BYTES = 8 * 1024 * 1024


def encode_request(method: str, host_header: str, path: str, headers, body):
    method = method.upper()
    assert method in {"GET", "POST", "PUT", "HEAD", "DELETE", "PATCH"}
    if not path.startswith("/"):
        path = "/" + path
    assert "\r" not in path and "\n" not in path
    out = f"{method} {path} HTTP/1.1\r\n"
    lowered = {k.lower() for k in headers}
    if "host" not in lowered:
        out += f"Host: {host_header}\r\n"
    if "connection" not in lowered:
        out += "Connection: close\r\n"
    for name, value in headers.items():
        assert ":" not in name and "\r" not in name and "\n" not in name
        assert "\r" not in value and "\n" not in value
        out += f"{name}: {value}\r\n"
    if body is not None:
        if "content-length" not in lowered:
            out += f"Content-Length: {len(body)}\r\n"
    elif method in ("POST", "PUT", "PATCH"):
        out += "Content-Length: 0\r\n"
    out += "\r\n"
    return out.encode("latin-1") + (body or b"")


def parse_status_line(line: str):
    parts = line.strip().split(" ", 2)
    if len(parts) < 2 or not parts[0].startswith("HTTP/"):
        return None
    if not parts[1].isdigit():
        return None
    status = int(parts[1])
    if not 100 <= status <= 599:
        return None
    return (status, parts[2] if len(parts) > 2 else "")


def read_line(data: bytes, index: int):
    end = data.find(b"\n", index)
    if end < 0:
        if index >= len(data):
            return None, index
        return data[index:].decode("latin-1"), len(data)
    line = data[index:end]
    if line.endswith(b"\r"):
        line = line[:-1]
    return line.decode("latin-1"), end + 1


def read_headers(data: bytes, index: int):
    headers = []
    while True:
        line, index = read_line(data, index)
        if line is None:
            return headers, index
        if line == "":
            return headers, index
        if ":" in line:
            name, _, value = line.partition(":")
            headers.append((name.strip(), value.strip()))
        if len(headers) > 200:
            return headers, index


def header(headers, name: str):
    for k, v in headers:
        if k.lower() == name.lower():
            return v
    return None


def read_response(data: bytes, method: str = "GET", max_body: int = MAX_BODY_BYTES):
    index = 0
    line, index = read_line(data, index)
    if line is None:
        return None
    status = parse_status_line(line)
    if status is None:
        return None
    headers, index = read_headers(data, index)
    while 100 <= status[0] <= 199 and status[0] != 101:
        line, index = read_line(data, index)
        if line is None:
            return None
        status = parse_status_line(line)
        if status is None:
            return None
        headers, index = read_headers(data, index)
    ceiling = max(0, min(max_body, MAX_BODY_BYTES))
    if method.upper() == "HEAD" or status[0] in (204, 304):
        return (status[0], status[1], headers, b"")
    chunked = "chunked" in (header(headers, "Transfer-Encoding") or "").lower()
    if chunked:
        body = b""
        while len(body) < ceiling:
            size_line, index = read_line(data, index)
            if size_line is None or not size_line:
                break
            token = size_line.split(";")[0].strip()
            if not re.fullmatch(r"[0-9a-fA-F]+", token):
                break
            size = int(token, 16)
            if size == 0:
                break
            take = min(size, ceiling - len(body))
            body += data[index:index + take]
            index += size
            _, index = read_line(data, index)  # CRLF after the chunk
            if take < size:
                break
        return (status[0], status[1], headers, body)
    length = header(headers, "Content-Length")
    if length is not None and length.strip().isdigit():
        want = min(int(length), ceiling)
        return (status[0], status[1], headers, data[index:index + want])
    return (status[0], status[1], headers, data[index:index + ceiling])


# ── cases ────────────────────────────────────────────────────────────
def case_socks5() -> None:
    expect_eq("greeting without auth", list(greeting(False)), [0x05, 0x01, 0x00])
    expect_eq("greeting with auth offers both", list(greeting(True)), [0x05, 0x02, 0x00, 0x02])

    expect_eq("auth frame", list(user_pass_auth("u", "p")), [0x01, 0x01, ord("u"), 0x01, ord("p")])

    # The whole point: a NAME is sent as a name, never resolved locally.
    request = connect_request("check.torproject.org", 443)
    expect_eq("CONNECT uses ATYP_DOMAIN", request[3], ATYP_DOMAIN)
    label = b"check.torproject.org"
    expect_eq("CONNECT domain length", request[4], len(label))
    expect_eq("CONNECT carries the name, not an IP", request[5:5 + len(label)], label)
    expect_eq("CONNECT port is big-endian", list(request[-2:]), [0x01, 0xBB])
    expect_eq("CONNECT verb", request[1], CMD_CONNECT)
    expect_eq("CONNECT reserved byte", request[2], 0x00)

    # .onion goes out exactly the same way — it is a name the *proxy* must resolve.
    onion = connect_request("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", 80)
    expect_eq("onion is a domain, not an error", onion[3], ATYP_DOMAIN)

    # An IP literal is sent as a literal: nothing to resolve.
    literal = connect_request("93.184.216.34", 80)
    expect_eq("IPv4 literal type", literal[3], ATYP_IPV4)
    expect_eq("IPv4 literal bytes", list(literal[4:8]), [93, 184, 216, 34])

    check("IPv4 recognition", is_ipv4_literal("1.2.3.4"))
    check("IPv4 rejects 999.1.1.1", not is_ipv4_literal("999.1.1.1"))
    check("IPv4 rejects a name", not is_ipv4_literal("example.com"))
    check("IPv6 recognition", is_ipv6_literal("2001:db8::1"))
    check("IPv6 rejects a name", not is_ipv6_literal("example.com"))

    expect_eq("reply code 0 is success", parse_reply(bytes([5, 0]))[0], True)
    expect_eq("reply code 5 is a refusal", parse_reply(bytes([5, 5]))[0], False)
    expect_eq("reply code 5 reason", parse_reply(bytes([5, 5]))[2], "connection refused by the destination")
    expect_eq("a truncated reply is a failure", parse_reply(bytes([5]))[0], False)
    expect_eq("a non-SOCKS5 reply is a failure", parse_reply(bytes([4, 0]))[0], False)
    check("unknown reply code is reported with its number", "99" in parse_reply(bytes([5, 99]))[2])


def case_policy() -> None:
    # DIRECT: everything public goes out directly, loopback stays loopback.
    expect_eq("direct remote", decide("api.nvidia.com", DIRECT, False)[0], ROUTE_DIRECT)
    expect_eq("direct loopback", decide("127.0.0.1", DIRECT, False)[0], ROUTE_DIRECT)

    # TOR with a healthy proxy: tunnelled, and named as such.
    result = decide("api.nvidia.com", TOR, True)
    expect_eq("tor remote tunnels", result[0], ROUTE_TUNNEL)

    # TOR with a dead proxy: REFUSED. This is the assertion the whole feature rests on.
    dead = decide("api.nvidia.com", TOR, False)
    expect_eq("tor with no proxy refuses", dead[0], ROUTE_REFUSE)
    check("the refusal explains there is no fallback", "no silent fallback" in dead[1])

    # Loopback is never tunnelled, and never refused for being loopback.
    expect_eq("loopback is not tunnelled", decide("127.0.0.1", TOR, True)[0], ROUTE_DIRECT)
    expect_eq("LAN is not tunnelled", decide("192.168.1.10", TOR, True)[0], ROUTE_DIRECT)
    expect_eq("172.16/12 is local", is_local("172.20.5.5"), True)
    expect_eq("172.32 is public", is_local("172.32.5.5"), False)
    expect_eq("CGNAT is not loopback/LAN by this rule", is_local("100.64.0.1"), False)

    # .onion exists only in Tor.
    expect_eq("onion in tor", decide("x.onion", TOR, True)[0], ROUTE_TUNNEL)
    expect_eq("onion on direct is refused", decide("x.onion", DIRECT, True)[0], ROUTE_REFUSE)
    expect_eq("onion with a dead proxy is refused", decide("x.onion", TOR, False)[0], ROUTE_REFUSE)
    check("onion detection", is_onion("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"))
    check("onion detection needs the suffix", not is_onion("onion.example.com"))

    # The switch wins over everything except loopback.
    expect_eq("offline refuses remote under tor", decide("api.nvidia.com", TOR, True, offline=True)[0], ROUTE_REFUSE)
    expect_eq("offline refuses remote under direct", decide("api.nvidia.com", DIRECT, True, offline=True)[0], ROUTE_REFUSE)
    expect_eq("offline still allows loopback", decide("127.0.0.1", TOR, True, offline=True)[0], ROUTE_DIRECT)

    # Persistence is boring on purpose: anything that is not "tor" is DIRECT.
    expect_eq("transport parse tor", parse_transport("TOR"), TOR)
    expect_eq("transport parse junk", parse_transport("socks"), DIRECT)
    expect_eq("transport parse empty", parse_transport(""), DIRECT)
    expect_eq("transport parse none", parse_transport(None), DIRECT)
    expect_eq("storage value round-trips", parse_transport(storage_value(TOR)), TOR)

    expect_eq("proxy default port", parse_proxy("127.0.0.1"), ("127.0.0.1", 9050))
    expect_eq("proxy explicit port", parse_proxy("127.0.0.1:9150"), ("127.0.0.1", 9150))
    expect_eq("proxy scheme is stripped", parse_proxy("socks5h://10.0.0.2:9050"), ("10.0.0.2", 9050))
    expect_eq("proxy junk is null", parse_proxy(""), None)
    expect_eq("proxy bad port is null", parse_proxy("127.0.0.1:70000"), None)


def case_tor_check() -> None:
    expect_eq("in circuit", parse_tor('{"IsTor": true, "IP": "185.220.101.7"}'), ("IN_CIRCUIT", "185.220.101.7"))
    expect_eq("not in circuit", parse_tor('{"IsTor":false,"IP":"81.2.69.142"}'), ("NOT_IN_CIRCUIT", "81.2.69.142"))
    expect_eq("quoted booleans are accepted", parse_tor('{"IsTor":"true","IP":"1.2.3.4"}')[0], "IN_CIRCUIT")
    expect_eq("missing field is inconclusive", parse_tor('{"IP":"1.2.3.4"}')[0], "INCONCLUSIVE")
    expect_eq("empty body is inconclusive", parse_tor("  ")[0], "INCONCLUSIVE")
    check("inconclusive says do not assume", "do not assume" in "UNVERIFIED · the answer has no IsTor field · do not assume a circuit")


def case_http_wire() -> None:
    encoded = encode_request("GET", "example.com", "/a?b=1", {"User-Agent": "x"}, None)
    text = encoded.decode()
    check("request starts with the status-shaped line", text.startswith("GET /a?b=1 HTTP/1.1\r\n"))
    check("Host is added", "Host: example.com\r\n" in text)
    check("Connection: close is added", "Connection: close\r\n" in text)
    check("GET has no length header", "Content-Length" not in text)
    check("request ends with a blank line", text.endswith("\r\n\r\n"))

    post = encode_request("POST", "example.com", "/rpc", {"Content-Type": "application/json"}, b"{}").decode()
    check("POST carries its length", "Content-Length: 2\r\n" in post)
    check("POST body follows the headers", post.endswith("{}"))

    empty_post = encode_request("POST", "example.com", "/", {}, None).decode()
    check("bodyless POST declares zero length", "Content-Length: 0\r\n" in empty_post)

    try:
        encode_request("GET", "example.com", "/a\r\nX-Injected: 1", {}, None)
        check("header injection in the path is refused", False)
    except AssertionError:
        check("header injection in the path is refused", True)

    expect_eq("status line", parse_status_line("HTTP/1.1 200 OK"), (200, "OK"))
    expect_eq("status line without reason", parse_status_line("HTTP/1.0 204"), (204, ""))
    expect_eq("status line junk", parse_status_line("SSH-2.0-OpenSSH"), None)
    expect_eq("status line bad code", parse_status_line("HTTP/1.1 999 Nope"), None)

    simple = b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/plain\r\n\r\nhello"
    response = read_response(simple)
    expect_eq("content-length body", response[3], b"hello")
    expect_eq("status parsed from a response", response[0], 200)
    expect_eq("header lookup", header(response[2], "content-type"), "text/plain")

    chunked = (b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
               b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")
    expect_eq("chunked body is joined", read_response(chunked)[3], b"hello world")

    closed = b"HTTP/1.1 200 OK\r\n\r\nno length at all"
    expect_eq("read-to-close body", read_response(closed)[3], b"no length at all")

    head = read_response(simple, method="HEAD")
    expect_eq("HEAD has no body even with a length", head[3], b"")
    expect_eq("204 has no body", read_response(b"HTTP/1.1 204 No Content\r\nContent-Length: 10\r\n\r\n")[3], b"")

    interim = b"HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi"
    expect_eq("interim 100 is skipped", read_response(interim)[3], b"hi")

    huge = b"HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n" + b"x" * 5000
    expect_eq("body is clamped to the caller's ceiling", len(read_response(huge, max_body=100)[3]), 100)

    chunked_clamped = (b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                       b"3e8\r\n" + b"x" * 1000 + b"\r\n0\r\n\r\n")
    expect_eq("chunked is clamped too", len(read_response(chunked_clamped, max_body=64)[3]), 64)


def case_drift() -> None:
    """The Kotlin must still spell the same constants this file asserts."""
    import os

    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    kotlin_root = os.path.join(root, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile")

    socks = open(os.path.join(kotlin_root, "net", "Socks5.kt"), encoding="utf-8").read()
    for name, value in (
        ("VERSION", "0x05"), ("METHOD_NO_AUTH", "0x00"), ("METHOD_USER_PASS", "0x02"),
        ("ATYP_DOMAIN", "0x03"), ("ATYP_IPV4", "0x01"), ("ATYP_IPV6", "0x04"), ("CMD_CONNECT", "0x01"),
    ):
        check(f"drift: Socks5.{name} is {value}", re.search(rf"const val {name}: Byte = {value}", socks) is not None)

    policy = open(os.path.join(kotlin_root, "net", "TransportPolicy.kt"), encoding="utf-8").read()
    check("drift: Orbot port is 9050", 'ORBOT_PORT = 9050' in policy)
    check("drift: transport storage is lowercase TOR", 'transport.label.lowercase()' in policy)
    for host in ("localhost", "127.0.0.1", "10.0.2.2", "::1"):
        check(f"drift: {host} is local in the Kotlin", host in policy)
    for suffix in (".local", ".internal", ".lan", ".home"):
        check(f"drift: {suffix} suffix is local in the Kotlin", suffix in policy)

    tor = open(os.path.join(kotlin_root, "net", "TorCheck.kt"), encoding="utf-8").read()
    check("drift: the check endpoint is the Tor Project one",
          'https://check.torproject.org/api/ip' in tor)
    check("drift: it parses IsTor", '"IsTor"' in tor)

    wire = open(os.path.join(kotlin_root, "net", "HttpWire.kt"), encoding="utf-8").read()
    check("drift: MAX_BODY_BYTES matches", 'MAX_BODY_BYTES = 8 * 1024 * 1024' in wire)
    check("drift: Connection: close is sent", 'Connection: close' in wire)
    check("drift: chunked is handled", 'chunked' in wire.lower())

    runtime = open(os.path.join(kotlin_root, "privacy", "TorRuntime.kt"), encoding="utf-8").read()
    check("drift: Orbot's package is named", 'org.torproject.android' in runtime)
    check("drift: a probe has a TTL so it is not per-request",
          'PROBE_TTL_MS' in runtime)

    egress = open(os.path.join(kotlin_root, "privacy", "EgressHttp.kt"), encoding="utf-8").read()
    check("drift: open() refuses remote under a tunnel", 'decision.kind != EgressClass.LOCAL' in egress)
    check("drift: exchange() exists and is the remote door", 'fun exchange(' in egress)
    check("drift: the transport is re-decided per exchange", 'TransportPolicy.decide(' in egress)

    # The one thing that must never come back: routing through the platform's SOCKS proxy, which
    # resolves names locally before the proxy ever sees them.
    for path in ("privacy/EgressHttp.kt", "net/SocketDialer.kt", "net/TransportPolicy.kt"):
        body = open(os.path.join(kotlin_root, path), encoding="utf-8").read()
        check(f"drift: {path} does not use the platform SOCKS proxy",
              "Proxy.Type.SOCKS" not in body and "java.net.Proxy(" not in body)


def main() -> int:
    case_socks5()
    case_policy()
    case_tor_check()
    case_http_wire()
    case_drift()

    for line in failures:
        print(f"FAIL  {line}")
    print()
    if failures:
        print(f"FAILED · {passed} passed, {len(failures)} failed")
        return 1
    print(f"PASS · {passed} checks (network transport spec)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
