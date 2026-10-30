# Privacy and identity — what this app actually hides, and what it cannot

Status: **the egress guard and ledger are implemented in the working tree, their logic is
host-verified, and the Compose panel has not been compiled by Gradle or run on a device.** The rest of
this document is a statement of limits, not a promise of anonymity.

## The question this file answers

"Can this device be made totally hidden — microG, a mock location, a mock IMEI?"

Short answer: **no, and not because any single piece is missing.** Hiding a phone is a property of the
*network path* and of what the user types, not of the identifiers the OS reports. Lucifer now enforces
what it can enforce — a switch, a choke point, and a record — and says plainly where it stops.

## What anonymity would actually require

A phone leaks identity in four layers. Lucifer is only ever able to touch the second and third.

| Layer | Who sees it | Does changing IMEI / location / microG help? |
| --- | --- | --- |
| Radio and subscriber | Carrier: IMSI, cell timing, billing identity | Not at all. The SIM and the tower are the identity. |
| Network path | Carrier, ISP, any Wi-Fi operator: destination, SNI, volume, timing | Partly: Lucifer's own traffic can ride Tor (`NETWORK_AND_TOR.md`). Everything else on the phone still needs an OS-level VPN. |
| Application content | Every server Lucifer talks to: prompts, pages, addresses | Partly — this is what the ledger and switch govern. |
| Device identifiers | Apps and servers that ask for them | Marginally. Modern Android gates IMEI behind `READ_PRIVILEGED_PHONE_STATE`; apps get `ANDROID_ID` or nothing. |

## What Lucifer now does

- **One choke point, enforced by structure.** `privacy/EgressHttp.kt` is the only file that may call
  `openConnection()`; it asks `privacy/NetworkGuard.kt` first. App code asks `EgressHttp` for a
  connection and gets either one or a refusal, so "remember to check" is no longer part of the job.
  `privacy/EgressLedger.kt` holds the rules and the record; it has no Android dependency so the rules
  can be executed on a host.
- **A real switch.** Settings → Privacy → *GO DARK · NETWORK OFF* writes
  `files/config/network_enabled.txt`. With it off, every non-local destination is refused **before**
  the connection is opened, and the caller returns an honest refusal line instead of a fake success.
- **Local traffic keeps working.** Loopback, RFC1918, link-local and `.local` destinations are allowed
  even with the switch off, so Termux, the VM bridge, the memory MCP and the Omega knowledge server do
  not break. A "privacy mode" that kills your own shell is a bug, not a feature.
- **The ledger redacts the one thing that would read like a diary.** It stores host and path, never the
  query string — a DuckDuckGo query is the user's question, and a log of those is a transcript. It
  records *that* a query existed, not what it was.
- **It is bounded and local.** 400 records in a ring, app-private storage, never uploaded, cleared from
  the same panel.
- **A new outbound call cannot quietly skip it.** `tools/privacy-core/audit_egress.py --check` reads the
  tree and fails if any socket is opened outside `privacy/EgressHttp.kt`, the single owner of
  `openConnection()`. Current state: 9 gated call sites, 1 socket owner, 0 outside the gate. Guarding
  used to be a convention each call site had to remember; now application code cannot reach the network
  without going through it.

## What it deliberately does not do

| Requested | Verdict | Why |
| --- | --- | --- |
| microG | **A device decision, not an app one** | `composeApp/build.gradle.kts` ships **zero** Google dependencies, so Lucifer needs nothing replaced — but microG is about the *other* apps on the phone, and that is a legitimate choice. See [MICROG_AND_PLAY_SERVICES.md](MICROG_AND_PLAY_SERVICES.md); note that microG's push still reaches Google. |
| Mock location | Refused as theater | No location permission is declared and no location API is called, so there is nothing to mock within Lucifer. At the OS level a mock provider only feeds the app-facing `LocationManager`; the modem and Wi-Fi positioning databases still locate the handset. |
| Mock IMEI | Refused | IMEI lives behind the radio and a privileged permission. Changing it means baseband commands or root-level telephony hooks: it bricks modems, breaks OTA and attestation, and is illegal in the US, UK and EU. It is also useless against the layers above. Lucifer will not implement it. |
| Tor for Lucifer's own traffic | **Implemented** | `privacy/TorRuntime.kt` + `net/Socks5.kt`: when TOR is selected, every remote request he makes goes through a SOCKS5 proxy (Orbot, loopback) with the **hostname sent unresolved**, so this phone never looks up a name it meant to keep private. If the proxy dies the request is refused, never sent direct. See [NETWORK_AND_TOR.md](NETWORK_AND_TOR.md). |
| Device-wide VPN | **Deliberately not implemented** | A `VpnService` needs a userspace TCP/IP stack and `protect()` discipline; a half-built one reports "connected" and leaks. Use Orbot's VPN mode or WireGuard — Lucifer reads `TRANSPORT_VPN` and reports which is running instead of pretending to be it. |
| Spoofed `ANDROID_ID` / attestation | Not implemented | Faking platform identifiers means hooking the framework, which is exactly what makes a device *distinctive*. |

## Attestation: this APK is identifiable on purpose

"Hidden" and "an agent with shell access" pull in opposite directions. These dependencies are in the
APK, and each one makes the device easier to recognise, not harder:

- `org.lsposed.hiddenapibypass` — bypasses the hidden-API blocklist. Play Integrity and several banking
  apps treat its presence as tampering.
- `Dhizuku-API` — borrows a device-owner identity. A third-party device owner is not a stock device and
  is visible to anything MDM-aware.
- `dev.rikka.shizuku` — a user-authorized ADB shell bridge. Legitimate, but it means a shell runs with
  UID 2000 under this app's direction.

If the goal is to be unremarkable to a careful observer, these three are the first things to remove —
not the IMEI. `tools/privacy-core/audit_egress.py` prints them from the build file each run so the
trade-off stays visible.

## What to do instead, in order of effect

1. **Put the whole device behind a VPN or Tor**, chosen at the OS level (Orbot's VPN mode, or a travel router), so it
   covers Lucifer, the browser and everything else. This is the only measure that touches layer 2.
2. **Assume Lucifer's remote calls are attributable.** The NVIDIA key in `config/nvidia_key.txt` ties
   every prompt to an account. Use a separate key and a separate account from anything personal.
3. **Turn the camera context off** in Heartbeat unless you need it. A JPEG leaving the device is the
   single most identifying payload this app can produce.
4. **Read the ledger** after a day of normal use. It answers "what does this phone tell the world"
   with the app's own behaviour instead of a guess.
5. **Use the switch** when you do not need the model. Local shell, local files and the sigil clock all
   keep working with the network off.
6. **Treat the identity questions as content questions.** No identifier change survives a prompt that
   says who you are, where you live and what you are working on.

## Verification

```bash
./scripts/verify_privacy_core.sh     # spec (71 checks) + Kotlin harness if a JVM exists + audit
python3 tools/privacy-core/spec_check.py          # rules only
python3 tools/privacy-core/audit_egress.py        # call sites, flows, attestation flags
python3 tools/privacy-core/audit_egress.py --check # non-zero if any socket skips the guard
```

What is **not** verified: `NetworkGuard.kt` and `EgressHttp.kt` have never been compiled by AGP or executed on a device, and
the Compose card in `SettingsPanel` has never been rendered. The Kotlin harness
(`tools/privacy-core/EgressCoreTests.kt`) also has not run in this workspace — there is no JVM here —
so the Python mirror in `tools/privacy-core/spec_check.py` is what currently passes. If those two ever
disagree, the Kotlin is what ships and the mirror is wrong.

## Limits worth stating out loud

- The guard is a **cooperative** choke point. Code that opens a socket without asking it is not blocked
  by anything except the audit script. That is why the audit is part of the verify script.
- The switch does not stop the OS, other apps, or Android's own telemetry.
- The ledger is a local claim, not a network capture. It records what Lucifer *decided*; it cannot prove
  what the kernel did afterwards.
- If `LuciferApplication.onCreate` never runs, egress is unguarded. The panel reports
  `GUARD NOT INSTALLED` rather than pretending otherwise.
