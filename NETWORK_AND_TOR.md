# Transport, Tor, and why there is no VpnService

Lucifer can now send **his own** traffic through Tor. That is a real, tested, fail-closed feature. It is
not a device-wide VPN, and this document is mostly about the difference, because the difference is
where privacy tools usually turn into privacy theatre.

---

## 1. What was built

| Piece | File | What it does |
|---|---|---|
| SOCKS5 client (RFC 1928 + 1929) | `net/Socks5.kt` | byte-exact frames; a **name is sent as a name** (ATYP_DOMAIN), never pre-resolved |
| Dialer | `net/SocketDialer.kt` | the only file that constructs a socket; direct, or a tunnel with TLS layered on it |
| Routing policy | `net/TransportPolicy.kt` | DIRECT / TOR per destination; `.onion`; loopback; **refuse, never fall back** |
| HTTP/1.1 client | `net/HttpWire.kt` | request encoding, chunked/content-length/read-to-close, hard byte ceilings, no redirects |
| Tor evidence | `net/TorCheck.kt` | parses Tor Project's `IsTor` answer; four distinct verdicts, no false comfort |
| Android runtime | `privacy/TorRuntime.kt` | Orbot detection, proxy probe with a TTL, mode persistence, circuit test, status line |
| The door | `privacy/EgressHttp.kt` | `exchange()` for everything remote; `open()` demoted to loopback-only |

Settings → **Privacy** now has a **TRANSPORT** row: `VIA TOR` / `USE DIRECT`, a `TEST CIRCUIT` button, a
status line, and the scope note. The mode is one file (`config/egress_transport.txt`); the proxy is one
file (`config/egress_proxy.txt`, default `127.0.0.1:9050`).

### Setup

1. Install **Orbot** (the maintained Android Tor client) — Lucifer does not bundle a Tor binary.
2. In Orbot: turn Tor on, enable the **SOCKS proxy** on port 9050.
3. Lucifer → Settings → Privacy → **VIA TOR**, then **TEST CIRCUIT**. It must read
   `IN CIRCUIT · exit <address>`; anything else stays visible as a refusal.

---

## 2. The leak this feature exists to avoid

Every JVM/Android HTTP client that supports SOCKS resolves the hostname **before** handing the request
to the proxy. `HttpURLConnection` with `Proxy.Type.SOCKS` is exactly that: the proxy receives an
already-resolved IP. The connection then looks anonymous while the phone's own resolver has just been
told every site you visited. That is not a subtle flaw; it defeats the entire purpose.

So the tunnel is hand-built:

- the hostname goes to the proxy as `ATYP_DOMAIN`, and the phone resolves nothing;
- for names that are already literals there is nothing to resolve, and they are sent as literals;
- TLS is layered over the tunnel with `endpointIdentificationAlgorithm = "HTTPS"`, so a certificate
  from any trusted CA is still not accepted for the wrong host;
- `java.net.Proxy`, `InetAddress.getByName` and friends are **banned from the transport files** by
  `scripts/verify_net_core.sh` part 4.

One place did resolve names on purpose: the browser's public-address check (`getAllByName` before
connecting, to refuse loopback/private targets). With Tor on that check would leak every destination, so
it is now conditional — in TOR mode the browser refuses **literals** textually and lets the proxy resolve
names, which is safe because the proxy connects from its own network where `192.168.x.x` is not your LAN.

### No fallback, ever

If Tor is selected and the proxy stops answering, requests are **refused**:

> TOR is selected but the proxy is not answering, so nothing was sent. There is no silent fallback to a
> direct connection — that would defeat the point of choosing Tor.

That is asserted in three places: `TransportPolicy.decide` (Kotlin), `tools/net-core/spec_check.py`
(Python mirror), and `tools/net-core/NetCoreTests.kt` (Kotlin harness). A proxy that is configured but
dead must never look like the working case.

---

## 3. What Tor actually covers here

| Traffic | Goes through Tor when enabled |
|---|---|
| Model prompts and completions (Nemotron) | **yes** |
| Page fetches (browser, recovery research) | **yes** |
| Wallet balance RPC | **yes** |
| Corpus sync from GitHub | **yes** |
| Lean audit-vault calls | **yes** |
| Tor circuit check | **yes** (it is egress; it appears in the ledger) |
| Termux, the VM over SSH, the Drive-memory MCP, the Omega server | no — loopback/LAN by design, and tunnelling them would break them |
| Other apps on the phone | **no** |
| The OS, the radio, the carrier | **no** |

**What it does not hide, and no app-level setting can:**

- **Who you are to the service.** The NVIDIA call carries your API key; the RPC node sees the wallet
  address; any logged-in service knows the account. A different exit node does not change any of that.
- **The device.** Carrier records, the OS, and the fact that this phone runs Lucifer.
- **Volume and timing.** Tor hides the path, not the pattern.
- **UDP and DNS from anything else.** Lucifer opens no UDP socket at all; other apps are not his business
  and not under his control.

The honest one-liner, which is also what the settings panel says:

> Tor changes the network path, not who you are to the services you already use.

---

## 4. Why there is no VpnService

A `VpnService` intercepts **IP packets**. Turning those into Tor circuits requires:

1. a userspace TCP/IP stack — handshake state machine, sequence numbers, retransmission, MSS clamping,
   window scaling, and TIME_WAIT handling;
2. a UDP path — either dropped (breaking QUIC, and some apps silently stop working) or converted to
   SOCKS5 UDP-associate (which Orbot supports and most of the internet does not);
3. `protect()` on every socket that must escape the tunnel — miss one and the app's tunnel loops through
   itself, which presents as "the VPN connects and nothing loads";
4. IPv6 handling, or a decision to break it;
5. a foreground service, a persistent notification, and per-app allow/deny policy;
6. and, crucially, it must be **tested against real apps**, because a VPN that leaks 5% of traffic while
   saying "connected" is worse than no VPN at all.

That is a months-long project, and a half-finished one is a liability with a green status light. So the
split is:

- **Lucifer's own traffic** → Tor via SOCKS5. Implemented here, and the part he can actually guarantee.
- **Device-wide tunnelling** → **Orbot's VPN mode** (Tor for everything, per-app included) or
  **WireGuard** with a provider you trust. Both are mature, both are someone else's full-time job.
- **What Lucifer does about it** → he *reports* it, read-only: `vpnPresent()` checks the active network
  for `TRANSPORT_VPN`, and the status line shows `system VPN present` / `no system VPN` next to the Tor
  state. He does not pretend to be the tunnel.

If you enable Orbot's VPN mode **and** Lucifer's TOR transport, you get Tor twice: the SOCKS5 proxy is
reached over loopback so it stays inside the VPN, and the traffic is still Tor. That is not a problem,
just redundant — the panel says so rather than hiding it.

---

## 5. A hole this work found

The stricter socket audit (`audit_egress.py`, now "one door out") immediately flagged a file the earlier
audit could not see: `services/cluster/ClusterManager.kt`.

- It opened an outbound `Socket(hostname, port)` to hardcoded LAN addresses — **never asking the guard**,
  so it was an egress path outside the ledger and outside GO DARK.
- It also started an **unauthenticated** `ServerSocket` on port 8023 that accepted text commands.
- Nothing referenced it: no caller, no manifest entry, no test.

It was deleted rather than repaired. A dead file that can be started by future code and silently ignore
the privacy switch is not a feature waiting to be finished; it is a hole waiting to be opened.

The audit now enforces four things and fails the build on any of them:

1. socket construction only in `net/SocketDialer.kt` and `privacy/EgressHttp.kt`;
2. `SocketDialer` callable only from `EgressHttp` and `TorRuntime`;
3. listening sockets only from the loopback allowlist (`VerificationServer`, bound to `127.0.0.1`);
4. `EgressHttp.open()` — the `HttpURLConnection` door — used only by the two loopback clients.

---

## 6. Verification status

Run in this workspace:

| Check | Result |
|---|---|
| `python3 tools/net-core/spec_check.py` | **PASS · 110 checks** |
| `./scripts/verify_net_core.sh` | **PASS** (Kotlin stage SKIPPED — no toolchain) |
| `python3 tools/privacy-core/audit_egress.py --check` | 10 gated sites · 2 socket owners · 0 outside the gate · 0 Google references |
| `python3 tools/privacy-core/spec_check.py` | **PASS · 90 checks** |
| `./scripts/verify_all.sh --quiet` | see README for the current line |

**Not verified — and this matters more here than anywhere else:**

- **No Kotlin has been compiled** in this workspace (no JDK/SDK), so `Socks5`, `SocketDialer`,
  `HttpWire`, `TransportPolicy`, `TorRuntime` and the six migrated call sites are review-only.
- **No real Tor circuit has been run** from this sandbox. The protocol is asserted byte-for-byte and the
  parsers are exercised against synthetic streams, but "Orbot answered and the exit was a Tor node" is a
  claim that needs a phone.
- The wire client is deliberately conservative (no HTTP/2, no connection reuse, `Connection: close`). If
  a server insists on HTTP/2-only it will fail — loudly, with a status of 0 and the reason recorded,
  never silently.

Treat TOR as **unproven until `TEST CIRCUIT` says `IN CIRCUIT` on your device**. That button exists
precisely because "the proxy port is open" is not evidence, and this document should not be either.
