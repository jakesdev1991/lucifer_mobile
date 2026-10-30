#!/usr/bin/env python3
"""Attestation and egress audit.

Two questions, both answered by reading the repository rather than trusting a document:

1. **Egress** — every place a socket is opened, and whether it asks `NetworkGuard` first.
2. **Attestation** — dependencies that make this APK *remarkable* to an observer (hidden-API bypass,
   device-owner delegation, Shizuku) and therefore make "totally hidden" harder rather than easier.

    python3 tools/privacy-core/audit_egress.py            # human-readable report
    python3 tools/privacy-core/audit_egress.py --markdown # the checked-in table for EGREGATE_AUDIT.md
    python3 tools/privacy-core/audit_egress.py --check    # exit 1 if a remote call site is unguarded

`--check` is the CI-shaped form: a new outbound call that forgets the guard fails the build instead of
being discovered later.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KOTLIN_ROOT = os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin")
GUARD = "NetworkGuard.authorize"
GATED = ("EgressHttp.open(", "EgressHttp.openApproved(", "EgressHttp.allowed(", "EgressHttp.exchange(")
SOCKET_OWNER = os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile",
                            "privacy", "EgressHttp.kt")

# Socket construction is allowed in exactly two files: the dialer that speaks SOCKS5, and the local-only
# door in EgressHttp. `SocketDialer` may only be called from those two. Everything remote goes through
# `EgressHttp.exchange`, and `EgressHttp.open` is for loopback clients only.
SOCKET_BUILDERS = {
    os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile", "net",
                 "SocketDialer.kt"): "the dialer (direct + SOCKS5 tunnel)",
    SOCKET_OWNER: "the local-only door and the exchange transport",
}
DIALER_CALLERS = {
    os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile", "privacy",
                 "EgressHttp.kt"),
    os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile", "privacy",
                 "TorRuntime.kt"),
}
# Loopback-only clients. These are the only files permitted to use the HttpURLConnection door, because
# their destinations never leave the device.
LOCAL_CLIENTS = {
    os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile", "memory",
                 "DriveMemoryMcpClient.kt"),
    os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile",
                 "omni_bridge", "knowledge", "OmegaKnowledgeClient.kt"),
}
SOCKET_TOKENS = ("Socket(", "SSLSocketFactory", "DatagramSocket(", "openConnection()")
# Listening sockets are inbound, not egress, but they are still a door into the process. Allowed only
# where the bind address is loopback by construction.
LISTENERS = {
    os.path.join("composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile", "verification",
                 "VerificationServer.kt"): "binds 127.0.0.1 for the Lean verifier's control plane",
}

# Destinations that are device-local by construction. A call site whose surrounding text only names
# these needs no guard to be correct, but it is reported separately rather than hidden.
LOCAL_MARKERS = ("127.0.0.1", "localhost", "10.0.2.2", "192.168.", "10.0.", "::1")

# Dependencies that change how the device looks to an observer.
ATTESTATION_FLAGS = {
    "org.lsposed.hiddenapibypass": (
        "Bypasses the hidden-API blocklist so reflection can reach non-SDK internals. "
        "Presence alone is a signal: Play Integrity and some banking apps treat it as tampering."
    ),
    "Dhizuku": (
        "Borrows a device-owner identity from an app the owner provisioned. A device with a "
        "third-party device owner is not a stock device, and that is visible to MDM-aware services."
    ),
    "dev.rikka.shizuku": (
        "A user-authorized ADB shell bridge. Legitimate, but it means a shell runs with UID 2000 "
        "under this app's direction — worth naming in any threat model."
    ),
    "com.auth0.android:jwtdecode": (
        "Decodes JWTs. It does not verify signatures, so nothing here should be trusted as proof of "
        "identity; recorded so the claim is not made by accident."
    ),
    "org.bouncycastle": (
        "Bundles its own crypto provider. Fine, but the app owns the failure modes; Android's "
        "consolidated keystore is the alternative."
    ),
}


# Nothing in this APK may acquire a Google dependency. microG is a *device* choice; the moment this
# app links GMS it starts being a Google client itself, on every device, whatever the ROM is.
GOOGLE_NEEDLES = (
    "com.google.android.gms",
    "play-services",
    "firebase",
    "com.google.firebase",
    "com.google.android.material",
)
GOOGLE_IMPORT = "import com.google.android.gms"


def google_creep() -> list[dict]:
    """Dependencies or imports that would make this build a Google client."""
    findings = []
    gradle = os.path.join(ROOT, "composeApp", "build.gradle.kts")
    if os.path.isfile(gradle):
        for number, line in enumerate(open(gradle, encoding="utf-8", errors="replace"), 1):
            stripped = line.strip()
            if stripped.startswith("//"):
                continue
            for needle in GOOGLE_NEEDLES:
                if needle in stripped:
                    findings.append({"where": "composeApp/build.gradle.kts", "line": number,
                                     "what": stripped[:100], "why": f"declares {needle}"})
    for path in kotlin_files():
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for number, line in enumerate(lines, 1):
            if line.strip().startswith(GOOGLE_IMPORT):
                findings.append({"where": os.path.relpath(path, ROOT), "line": number,
                                 "what": line.strip()[:100], "why": "imports the GMS client"})
    return findings


def kotlin_files() -> list[str]:
    found = []
    for base, _dirs, files in os.walk(KOTLIN_ROOT):
        for name in files:
            if name.endswith(".kt"):
                found.append(os.path.join(base, name))
    return sorted(found)


def call_sites(path: str) -> list[dict]:
    """Every `openConnection()` with the nearest guard above it."""
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError:
        return []
    sites = []
    for index, line in enumerate(lines):
        # Prose that merely names the method is not a socket.
        if line.lstrip().startswith(("*", "//", "/*")):
            continue
        if "openConnection()" not in line:
            continue
        guard_line = None
        for back in range(index - 1, max(-1, index - 60), -1):
            if GUARD in lines[back]:
                guard_line = back
                break
        window = "\n".join(lines[max(0, index - 12): index + 1])
        local_only = any(marker in window for marker in LOCAL_MARKERS) and guard_line is None
        # A guard anywhere earlier in the file still counts, but is reported as file-scoped so a
        # reviewer can see the difference between per-request and per-operation enforcement.
        file_guard = guard_line is None and any(GUARD in l for l in lines[:index])
        sites.append({
            "file": os.path.relpath(path, ROOT),
            "line": index + 1,
            "code": line.strip()[:96],
            "guard_line": guard_line + 1 if guard_line is not None else None,
            "file_guard": file_guard,
            "local_only": local_only,
            "status": "GUARDED" if guard_line is not None else (
                "FILE-GUARDED" if file_guard else ("LOCAL" if local_only else "UNGUARDED")
            ),
        })
    return sites


def gated_sites() -> list[dict]:
    """Every call site that goes through EgressHttp — the sanctioned way out."""
    found = []
    for path in kotlin_files():
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for index, line in enumerate(lines):
            if line.lstrip().startswith(("*", "//", "/*")):
                continue
            if any(marker in line for marker in GATED):
                verdict = [m for m in ("openApproved", "exchange", "open", "allowed") if f"EgressHttp.{m}(" in line][0]
                found.append({
                    "file": os.path.relpath(path, ROOT),
                    "line": index + 1,
                    "form": verdict,
                })
    return found


def gate_breaches() -> list[dict]:
    """A raw `openConnection()` outside EgressHttp is a socket that never asked.

    The old audit graded each site by looking for an `authorize` call above it, which means it could
    only judge the code it could recognise. Now there is one owner of the socket and any second file
    that opens one is a breach, whatever it is called.
    """
    owner = os.path.abspath(os.path.join(ROOT, SOCKET_OWNER))
    breaches = []
    for path in kotlin_files():
        if os.path.abspath(path) == owner:
            continue
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for index, line in enumerate(lines):
            if line.lstrip().startswith(("*", "//", "/*")):
                continue
            if "openConnection()" in line:
                breaches.append({
                    "file": os.path.relpath(path, ROOT),
                    "line": index + 1,
                    "code": line.strip()[:96],
                })
    return breaches


def socket_ownership() -> list[dict]:
    """Socket construction outside the two owner files, and dialer calls outside the two callers."""
    findings = []
    for path in kotlin_files():
        relative = os.path.relpath(path, ROOT)
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for index, line in enumerate(lines):
            if line.lstrip().startswith(("*", "//", "/*")):
                continue
            for node in ("SocketDialer.",):
                if node in line and relative not in DIALER_CALLERS:
                    findings.append({"file": relative, "line": index + 1, "code": line.strip()[:96],
                                     "why": "opens a socket through the dialer without going through the guard"})
            if relative in SOCKET_BUILDERS:
                continue
            if "ServerSocket(" in line and relative not in LISTENERS:
                findings.append({"file": relative, "line": index + 1, "code": line.strip()[:96],
                                 "why": "opens a listening socket that is not on the loopback allowlist"})
                continue
            if "ServerSocket(" in line:
                continue
            for token in SOCKET_TOKENS:
                if token in line:
                    findings.append({"file": relative, "line": index + 1, "code": line.strip()[:96],
                                     "why": f"constructs a socket outside {', '.join(sorted(SOCKET_BUILDERS))}"})
                    break
    return findings


def local_door_misuse() -> list[dict]:
    """`EgressHttp.open` is loopback-only; a third caller would bypass the transport decision."""
    findings = []
    for path in kotlin_files():
        relative = os.path.relpath(path, ROOT)
        if relative in LOCAL_CLIENTS or relative == os.path.relpath(os.path.join(ROOT, SOCKET_OWNER), ROOT):
            continue
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for index, line in enumerate(lines):
            if line.lstrip().startswith(("*", "//", "/*")):
                continue
            if "EgressHttp.open(" in line:
                findings.append({"file": relative, "line": index + 1, "code": line.strip()[:96]})
    return findings


def dependencies() -> list[dict]:
    path = os.path.join(ROOT, "composeApp", "build.gradle.kts")
    if not os.path.isfile(path):
        return []
    found = []
    for line in open(path, encoding="utf-8", errors="replace"):
        stripped = line.strip()
        if not stripped.startswith('implementation("'):
            continue
        coordinate = stripped.split('implementation("')[1].split('"')[0]
        for needle, why in ATTESTATION_FLAGS.items():
            if needle.lower() in coordinate.lower():
                found.append({"coordinate": coordinate, "why": why})
    return found


def egress_notes() -> list[dict]:
    """User-facing flows that leave the device, with the trigger a person would recognise."""
    path = os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin")
    flows = []

    def exists(rel: str) -> bool:
        return os.path.isfile(os.path.join(path, rel))

    if exists("omni_bridge/llm/NemotronClient.kt"):
        flows.append({"what": "Prompts, soul context and retrieved local memories", "where": "integrate.api.nvidia.com",
                      "when": "every chat message", "guarded": True})
        flows.append({"what": "One camera still (JPEG) plus trend text", "where": "integrate.api.nvidia.com",
                      "when": "heartbeat session with CAMERA consent", "guarded": True})
    if exists("resilience/WebResearch.kt"):
        flows.append({"what": "Search queries", "where": "html.duckduckgo.com",
                      "when": "recovery research after a command fails", "guarded": True})
    if exists("omni_bridge/heartbeat/HeartbeatService.kt"):
        flows.append({"what": "Page requests", "where": "github.com/trending, news.ycombinator.com",
                      "when": "heartbeat session", "guarded": True})
    if exists("data/wallet/LuciferWallet.kt"):
        flows.append({"what": "Wallet address lookup", "where": "public JSON-RPC nodes",
                      "when": "wallet panel balance refresh", "guarded": True})
    if exists("omni_bridge/knowledge/EmbeddedToE.kt"):
        flows.append({"what": "Repository tree + raw files", "where": "api.github.com, raw.githubusercontent.com",
                      "when": "corpus sync", "guarded": True})
    if exists("verification/AuditVault.kt"):
        flows.append({"what": "Audit payloads", "where": "the configured vault URL",
                      "when": "verification run", "guarded": True})
    return flows


def main() -> int:
    parser = argparse.ArgumentParser(description="Egress + attestation audit")
    parser.add_argument("--markdown", action="store_true", help="emit the checked-in table")
    parser.add_argument("--check", action="store_true", help="fail if a remote call site is unguarded")
    args = parser.parse_args()

    sites = []
    for file in kotlin_files():
        sites.extend(call_sites(file))
    sites.sort(key=lambda s: (s["file"], s["line"]))
    deps = dependencies()
    flows = egress_notes()
    unguarded = [s for s in sites if s["status"] == "UNGUARDED"]
    gated = gated_sites()
    breaches = gate_breaches()
    creep = google_creep()
    ownership = socket_ownership()
    door = local_door_misuse()

    if args.check:
        for site in sites:
            print(f"{site['status']:14} {site['file']}:{site['line']}")
        exit_code = 0
        if unguarded:
            print(f"\n{len(unguarded)} unguarded outbound call site(s) — every socket must ask NetworkGuard first.")
            exit_code = 1
        if breaches:
            for item in breaches:
                print(f"GATE-BREACH    {item['file']}:{item['line']}  {item['code']}")
            print(f"\n{len(breaches)} socket(s) opened outside EgressHttp — route them through EgressHttp.open().")
            exit_code = 1
        if creep:
            for item in creep:
                print(f"GOOGLE-CREEP   {item['where']}:{item['line']}  {item['why']}")
            print("\nThis build is degoogled by policy: no GMS, no Firebase, no Play Services dependency.")
            exit_code = 1
        if ownership:
            for item in ownership:
                print(f"SOCKET-OWNER   {item['file']}:{item['line']}  {item['code']}")
                print(f"               {item['why']}")
            print("\nSockets belong to net/SocketDialer.kt and privacy/EgressHttp.kt, and only EgressHttp or")
            print("TorRuntime may call the dialer.")
            exit_code = 1
        if door:
            for item in door:
                print(f"LOCAL-DOOR     {item['file']}:{item['line']}  {item['code']}")
            print("\nEgressHttp.open() is a loopback-only door. Remote requests use EgressHttp.exchange(),")
            print("because only exchange() decides the transport; open() would connect directly under Tor.")
            exit_code = 1
        if exit_code == 0:
            print(f"\nOK · {len(gated)} gated call sites · {len(SOCKET_BUILDERS)} socket owners · none unguarded · "
                  f"no Google dependencies")
        return exit_code

    if args.markdown:
        print("| Call site | Line | Guard | Status |")
        print("| --- | --- | --- | --- |")
        for site in sites:
            guard = f"line {site['guard_line']}" if site["guard_line"] else ("earlier in file" if site["file_guard"] else "—")
            print(f"| `{site['file'].split('com/lucifer/mobile/')[-1]}` | {site['line']} | {guard} | {site['status']} |")
        return 0 if not unguarded and not breaches else 1

    print("EGRESS — every socket, and whether it asks first\n")
    width = max((len(s["file"].split("com/lucifer/mobile/")[-1]) for s in sites), default=10)
    for site in sites:
        short = site["file"].split("com/lucifer/mobile/")[-1]
        print(f"  {site['status']:14} {short:<{width}} :{site['line']:<5} {site['code']}")

    print("\nWHAT LEAVES, AND WHEN\n")
    for flow in flows:
        print(f"  {flow['where']:<44} {flow['when']}")
        print(f"  {'':<44} carries: {flow['what']}")

    print("\nATTESTATION — dependencies that change how the device looks\n")
    if not deps:
        print("  none found")
    for dep in deps:
        print(f"  {dep['coordinate']}")
        print(f"    {dep['why']}")

    print("\nDEGOOGLED BY POLICY\n")
    if not creep:
        print("  no com.google.android.gms, play-services or firebase references in the app or the build")
    for item in creep:
        print(f"  {item['where']}:{item['line']}  {item['why']}")
        print(f"    {item['what']}")

    print("\nTRANSPORT — who may open the socket\n")
    for path, why in sorted(SOCKET_BUILDERS.items()):
        print(f"  {path.split('com/lucifer/mobile/')[-1]:<34} {why}")
    print(f"  dialer callers: {', '.join(sorted(p.split('com/lucifer/mobile/')[-1] for p in DIALER_CALLERS))}")
    print(f"  loopback-only HttpURLConnection clients: "
          f"{', '.join(sorted(p.split('com/lucifer/mobile/')[-1] for p in LOCAL_CLIENTS))}")
    for item in ownership:
        print(f"  SOCKET-OWNER {item['file']}:{item['line']}  {item['why']}")
    for item in door:
        print(f"  LOCAL-DOOR   {item['file']}:{item['line']}  {item['code']}")

    print()
    print(f"{len(gated)} gated call sites · {len(SOCKET_BUILDERS)} socket owners · {len(breaches)} outside the gate · "
          f"{len(unguarded)} unguarded · {len(ownership)} misplaced sockets · {len(door)} local-door misuses · "
          f"{len(deps)} attestation flags · {len(creep)} google references")
    return 0 if not unguarded and not breaches and not creep and not ownership and not door else 1


if __name__ == "__main__":
    sys.exit(main())
