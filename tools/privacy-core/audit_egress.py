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

    if args.check:
        for site in sites:
            print(f"{site['status']:14} {site['file']}:{site['line']}")
        if unguarded:
            print(f"\n{len(unguarded)} unguarded outbound call site(s) — every socket must ask NetworkGuard first.")
            return 1
        print(f"\nOK · {len(sites)} call sites, none unguarded")
        return 0

    if args.markdown:
        print("| Call site | Line | Guard | Status |")
        print("| --- | --- | --- | --- |")
        for site in sites:
            guard = f"line {site['guard_line']}" if site["guard_line"] else ("earlier in file" if site["file_guard"] else "—")
            print(f"| `{site['file'].split('com/lucifer/mobile/')[-1]}` | {site['line']} | {guard} | {site['status']} |")
        return 0 if not unguarded else 1

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

    print()
    print(f"{len(sites)} call sites · {len(unguarded)} unguarded · {len(deps)} attestation flags")
    return 0 if not unguarded else 1


if __name__ == "__main__":
    sys.exit(main())
