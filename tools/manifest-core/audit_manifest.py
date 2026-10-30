#!/usr/bin/env python3
"""Every permission the APK declares must be claimed by code that runs, or be one Android grants
itself with no call site at all.

The rule exists because a manifest is a promise. `BLUETOOTH_SCAN` in a private assistant's manifest
tells the store, the installer, and anyone who reads it that this app scans for devices; when nothing
calls the Bluetooth API the promise is false, and false promises are exactly what this project refuses
to ship. The same argument applies to `NFC`, to deprecated aliases kept "just in case", and to storage
permissions held while the export path uses the system file picker instead.

Two lists, and nothing in between:

  PLATFORM_GRANTED
      Permissions Android enforces without an API this code calls. `INTERNET` is checked by the kernel
      when a socket opens; `RECEIVE_BOOT_COMPLETED` by the launcher when it delivers the broadcast;
      `POST_NOTIFICATIONS` by the notification manager when a notification is posted. Requiring a
      literal call site for these would be noise, so each one below names where it is consumed.

  CALL_SITE
      Permissions that mean "this app touches something private". Each must have a matching string in
      the Kotlin sources — a permission check, an `NfcAdapter`, a `SpeechRecognizer`, a `BiometricPrompt`.
      A pattern is not the API call itself; it is the evidence that the feature exists.

Run:
    python3 tools/manifest-core/audit_manifest.py            # audit
    python3 tools/manifest-core/audit_manifest.py --check    # audit, non-zero on findings
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST = os.path.join(ROOT, "composeApp", "src", "androidMain", "AndroidManifest.xml")
SOURCES = os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin")

# permission -> where the OS consumes it (documented, not a call site)
PLATFORM_GRANTED = {
    "android.permission.INTERNET":
        "enforced by the kernel on every socket; all traffic goes through net/EgressHttp",
    "android.permission.ACCESS_NETWORK_STATE":
        "read via ConnectivityManager for the VPN/Tor checks in net/TransportPolicy",
    "android.permission.FOREGROUND_SERVICE":
        "required to call startForeground, which FloatingChatService does",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE":
        "declares the type used by FloatingChatService's startForeground",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC":
        "declares the type used by the heartbeat service",
    "android.permission.FOREGROUND_SERVICE_CAMERA":
        "declares the type used when the camera context service runs",
    "android.permission.SYSTEM_ALERT_WINDOW":
        "the floating chat overlay; granted by the user in Settings, never requested in code",
    "android.permission.WAKE_LOCK":
        "held by the foreground services while a turn is running",
    "android.permission.RECEIVE_BOOT_COMPLETED":
        "delivered to the boot receiver, which restarts the heartbeat",
    "android.permission.POST_NOTIFICATIONS":
        "checked at the notification manager when the tray entry is posted",
    "android.permission.ACCESS_LOCAL_NETWORK":
        "Android 16 gates the loopback site served by terminal/LocalWebsiteManager",
    "moe.shizuku.manager.permission.API_V23":
        "Shizuku-side permission; the grant dialog is Shizuku's, not this app's",
    "com.termux.permission.RUN_COMMAND":
        "Termux-side permission; Termux shows the prompt and the app only sends the intent",
}

# permission -> substrings that must appear somewhere in the Kotlin sources
CALL_SITE = {
    "android.permission.CAMERA": ["Manifest.permission.CAMERA", "ImageReader"],
    "android.permission.RECORD_AUDIO": ["Manifest.permission.RECORD_AUDIO", "SpeechRecognizer"],
    "android.permission.USE_BIOMETRIC": ["BiometricPrompt"],
}

PERMISSION_RE = re.compile(r'<uses-permission\s+android:name="([^"]+)"')


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def all_kotlin() -> dict[str, str]:
    found: dict[str, str] = {}
    for directory, _, files in os.walk(SOURCES):
        for name in files:
            if name.endswith(".kt"):
                path = os.path.join(directory, name)
                found[path] = read(path)
    return found


def main() -> int:
    findings: list[str] = []
    notes: list[str] = []

    for required in (MANIFEST, SOURCES):
        if not os.path.exists(required):
            print(f"FAIL  missing {required}", file=sys.stderr)
            return 2

    manifest = read(MANIFEST)
    declared = PERMISSION_RE.findall(manifest)
    sources = all_kotlin()
    corpus = "\n".join(sources.values())

    duplicates = {name for name in declared if declared.count(name) > 1}
    for name in sorted(duplicates):
        findings.append(f"declared more than once: {name}")

    for name in declared:
        if name in PLATFORM_GRANTED:
            notes.append(f"platform-granted  {name}\n            ↳ {PLATFORM_GRANTED[name]}")
            continue
        patterns = CALL_SITE.get(name)
        if patterns is None:
            findings.append(
                f"no justification: {name}\n"
                f"            either wire it and add a CALL_SITE entry, or remove it from the manifest"
            )
            continue
        missing = [pattern for pattern in patterns if pattern not in corpus]
        if missing:
            findings.append(
                f"declared but dead: {name}\n"
                f"            expected to find {missing} in the Kotlin sources"
            )
        else:
            notes.append(f"wired             {name}  ({', '.join(patterns)})")

    # The reverse direction: a withdrawn permission must not creep back through a source file that
    # still requests it at runtime, which would fail silently instead of loudly.
    for withdrawn, pattern in {
        "BLUETOOTH": "Manifest.permission.BLUETOOTH",
        "NFC": "Manifest.permission.NFC",
        "READ_EXTERNAL_STORAGE": "Manifest.permission.READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE": "Manifest.permission.WRITE_EXTERNAL_STORAGE",
        "USE_FINGERPRINT": "Manifest.permission.USE_FINGERPRINT",
    }.items():
        if pattern in corpus:
            findings.append(
                f"{withdrawn} was withdrawn from the manifest but the sources still request {pattern}"
            )

    for line in notes:
        print(f"  ok    {line}")
    for line in findings:
        print(f"  FAIL  {line}", file=sys.stderr)
    print()
    print(f"{len(declared)} permissions declared · {len(notes)} justified · {len(findings)} findings")

    if findings and "--check" in sys.argv:
        return 1
    if findings:
        return 1
    print("MANIFEST OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
