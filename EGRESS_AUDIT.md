# Egress audit — generated

Regenerate with:

```bash
python3 tools/privacy-core/audit_egress.py --markdown   # the call-site table
python3 tools/privacy-core/audit_egress.py              # flows, attestation, Google policy
python3 tools/privacy-core/audit_egress.py --check      # non-zero on an unguarded socket or a Google dep
```

There is exactly one owner of a socket: `privacy/EgressHttp.kt`. Every other file calls
`EgressHttp.open(...)`, which authorises first and hands back either a connection or a refusal. Any
`openConnection()` outside that one file is reported as `GATE-BREACH` and fails `--check`, whatever the
file is called.

`open` = authorise this request, then connect. `openApproved` = connect under a decision the caller
already holds, for one user action that legitimately makes several attempts (a model turn retrying
across models and keys, a corpus sync fetching many files). It never skips the decision, it reuses it,
so the ledger reads as "the user asked; N attempts" rather than N identical rows.

The previous revision graded sites by looking for an `authorize` call above them. That could only
judge the shapes the audit recognised, and it counted the browser's per-hop redirect loop and the
corpus loop as single sites. Both facts are why the guard is now structural instead of conventional.

## Gated call sites

| Call site | Form | Line |
| --- | --- | --- |
| `data/browser/LuciferBrowser.kt` | `open` | 52 |
| `data/wallet/LuciferWallet.kt` | `open` | 156 |
| `memory/DriveMemoryMcpClient.kt` | `open` | 156 |
| `omni_bridge/knowledge/EmbeddedToE.kt` | `openApproved` | 177 |
| `omni_bridge/knowledge/EmbeddedToE.kt` | `openApproved` | 224 |
| `omni_bridge/knowledge/OmegaKnowledgeClient.kt` | `open` | 44 |
| `omni_bridge/knowledge/OmegaKnowledgeClient.kt` | `open` | 80 |
| `omni_bridge/llm/NemotronClient.kt` | `openApproved` | 188 |
| `verification/AuditVault.kt` | `open` | 242 |

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


`--check` → 9 gated call sites · 1 socket owner · 0 sockets outside the gate · 5 attestation
dependencies · 0 Google references.
