#!/usr/bin/env bash
# Host-side verification for the network transport: SOCKS5, the routing policy, Tor verdicts and the
# HTTP client the tunnel speaks.
#
# Four parts, because four different things can be wrong:
#
#   1. the rules, executed as a spec (Python mirror — always runs)
#   2. the same rules executed against the real Kotlin (only when kotlinc + java are present)
#   3. the tunnel leaves through one door: no socket outside the dialer, no remote use of the
#      loopback-only `EgressHttp.open`, and no platform SOCKS proxy (which resolves names locally)
#   4. the Tor path does not resolve what it promised to leave to the proxy
#
#   ./scripts/verify_net_core.sh
#
# Exit code is 0 only if every part holds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NET="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/net"
HARNESS="$ROOT/tools/net-core/NetCoreTests.kt"
SPEC="$ROOT/tools/net-core/spec_check.py"
AUDIT="$ROOT/tools/privacy-core/audit_egress.py"
OUT="${TMPDIR:-/tmp}/lucifer-net-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
have_kotlin=0
if command -v "$KOTLINC_BIN" >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  have_kotlin=1
fi

status=0

echo "== 1. network transport spec (python mirror) =="
if python3 "$SPEC"; then :; else status=1; fi

echo
echo "== 2. network transport (real Kotlin) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  mkdir -p "$OUT"
  if "$KOTLINC_BIN" "$NET/Socks5.kt" "$NET/TransportPolicy.kt" "$NET/TorCheck.kt" "$NET/HttpWire.kt" \
      "$HARNESS" -d "$OUT/net-tests.jar" -nowarn; then
    STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/net-tests.jar:$STDLIB" com.lucifer.mobile.net.harness.NetCoreTestsKt || status=1
    else
      java -cp "$OUT/net-tests.jar" com.lucifer.mobile.net.harness.NetCoreTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the transport core" >&2
    status=1
  fi
else
  if [[ "${LUCIFER_REQUIRE_KOTLIN:-0}" == "1" ]]; then
    echo "FAILED — LUCIFER_REQUIRE_KOTLIN=1 but kotlinc and/or java not found." >&2
    status=1
  fi
  echo "SKIPPED — kotlinc and/or java not found. The Python spec above covers the same cases;"
  echo "          the Kotlin harness has NOT run in this workspace. Set KOTLINC=/path/to/kotlinc."
fi

echo
echo "== 3. one door out =="
if python3 "$AUDIT" --check; then :; else status=1; fi

echo
echo "== 4. the Tor path resolves nothing it promised not to =="
python3 - "$ROOT" <<'PY'
import os, re, sys

root = sys.argv[1]
kotlin_root = os.path.join(root, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile")
problems = []

# The platform SOCKS proxy resolves the destination before the proxy sees it, which is the leak this
# whole feature exists to avoid. It must not appear anywhere near the transport.
for relative in ("privacy/EgressHttp.kt", "net/SocketDialer.kt", "net/TransportPolicy.kt",
                 "net/Socks5.kt", "privacy/TorRuntime.kt"):
    path = os.path.join(kotlin_root, relative)
    # Prose is allowed to name the thing it is explaining; only real code counts.
    lines = [line for line in open(path, encoding="utf-8").read().splitlines()
             if not line.lstrip().startswith(("*", "//", "/*"))]
    body = "\n".join(lines)
    for needle in ("Proxy.Type.SOCKS", "java.net.Proxy(", "InetAddress.getByName("):
        if needle in body:
            problems.append(f"{relative} uses {needle}")

# The browser is the one place that resolves a host for its public-address check. That check must be
# conditional on the transport, or every page fetch leaks its destination while Tor is on.
browser = open(os.path.join(kotlin_root, "data", "browser", "LuciferBrowser.kt"), encoding="utf-8").read()
if "InetAddress.getAllByName(host)" in browser:
    guard = re.search(r"if \(TorRuntime\.transport\(\) == EgressTransport\.TOR\) \{(?:.|\n)*?return uri\.toString\(\)\s*\}",
                      browser)
    if guard is None:
        problems.append("LuciferBrowser resolves the host without checking the transport first")
else:
    problems.append("LuciferBrowser no longer resolves the host at all — did the public-address check vanish?")

# A destination that is sent through the tunnel must be sent as a name.
dialer = open(os.path.join(kotlin_root, "net", "SocketDialer.kt"), encoding="utf-8").read()
if "Socks5.connectRequest(" not in dialer:
    problems.append("SocketDialer never builds a SOCKS5 CONNECT")

for item in problems:
    print(f"FAIL  {item}")
if problems:
    print(f"\n{len(problems)} transport problem(s).")
    sys.exit(1)
print("OK · the tunnel is built from names, and the one local lookup is transport-gated")
PY
if [[ "$?" -ne 0 ]]; then status=1; fi

echo
if [[ "$status" -eq 0 ]]; then
  echo "PASS · network transport"
else
  echo "FAILED · network transport" >&2
fi
exit "$status"
