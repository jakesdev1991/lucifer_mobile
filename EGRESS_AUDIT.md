# Egress audit — generated

Regenerate with:

```bash
python3 tools/privacy-core/audit_egress.py --markdown   # the call-site table
python3 tools/privacy-core/audit_egress.py              # flows, attestation, Google policy
python3 tools/privacy-core/audit_egress.py --check      # non-zero on an unguarded socket or a Google dep
```

`GUARDED` = `NetworkGuard.authorize(...)` immediately above the `openConnection()`.
`FILE-GUARDED` = a guard exists earlier in the same file; acceptable for a private helper reachable only
through guarded callers, listed separately so it is a decision rather than a default. `LOCAL` = the
surrounding code only names loopback/private hosts. `UNGUARDED` fails the verify script.

## Outbound call sites

| Call site | Line | Guard | Status |
| --- | --- | --- | --- |
| `data/browser/LuciferBrowser.kt` | 66 | line 52 | GUARDED |
| `data/wallet/LuciferWallet.kt` | 164 | line 157 | GUARDED |
| `memory/DriveMemoryMcpClient.kt` | 162 | line 157 | GUARDED |
| `omni_bridge/knowledge/EmbeddedToE.kt` | 178 | line 170 | GUARDED |
| `omni_bridge/knowledge/EmbeddedToE.kt` | 212 | line 170 | GUARDED |
| `omni_bridge/knowledge/EmbeddedToE.kt` | 215 | line 170 | GUARDED |
| `omni_bridge/knowledge/OmegaKnowledgeClient.kt` | 45 | line 44 | GUARDED |
| `omni_bridge/knowledge/OmegaKnowledgeClient.kt` | 77 | line 72 | GUARDED |
| `omni_bridge/llm/NemotronClient.kt` | 186 | earlier in file | FILE-GUARDED |
| `verification/AuditVault.kt` | 247 | line 243 | GUARDED |

## What leaves the device, and when

| Destination | Trigger | Carries |
| --- | --- | --- |
| Termux / VM bridge / memory MCP / Omega server | local commands | stays on the device or the LAN |

## Attestation flags

Dependencies that make this APK *recognisable*. They are listed because "hidden" and "an agent with
shell access" pull in opposite directions — see [PRIVACY_AND_IDENTITY.md](PRIVACY_AND_IDENTITY.md).

| Dependency | Why it makes the device recognisable |
| --- | --- |
| `org.bouncycastle:bcprov-jdk18on:1.78.1` | Bundles its own crypto provider. Fine, but the app owns the failure modes; Android's consolidated keystore is the alternative. |
| `com.auth0.android:jwtdecode:2.1.0` | Decodes JWTs. It does not verify signatures, so nothing here should be trusted as proof of identity; recorded so the claim is not made by accident. |
| `dev.rikka.shizuku:api:13.1.5` | A user-authorized ADB shell bridge. Legitimate, but it means a shell runs with UID 2000 under this app's direction — worth naming in any threat model. |
| `io.github.iamr0s:Dhizuku-API:2.5.4` | Borrows a device-owner identity from an app the owner provisioned. A device with a third-party device owner is not a stock device, and that is visible to MDM-aware services. |
| `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` | Bypasses the hidden-API blocklist so reflection can reach non-SDK internals. Presence alone is a signal: Play Integrity and some banking apps treat it as tampering. |

## Degoogled by policy

No `com.google.android.gms`, `play-services` or `firebase` reference exists in the app or the
build, and `--check` fails if one is added. microG and Play Services are therefore a *device*
choice that cannot silently turn this build into a Google client — see
[MICROG_AND_PLAY_SERVICES.md](MICROG_AND_PLAY_SERVICES.md).


`--check` → 10 call sites · 0 unguarded · 5 attestation dependencies · 0 Google references.
