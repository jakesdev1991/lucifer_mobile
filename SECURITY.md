# Security notes

## Credentials

- Never put provider credentials, wallet private keys, or SSH passwords in source, assets, logs, or issue reports.
- NVIDIA API credentials and VM SSH passwords are stored as AES-GCM ciphertext using a non-exportable Android Keystore key. Legacy plaintext files are migrated on first read.
- Wallet private keys are stored separately from wallet metadata and encrypted with the same Keystore-backed store. The current wallet UI has no recovery phrase/export; do not fund these wallets.
- VM SSH uses JSch `StrictHostKeyChecking=yes` and an app-private `known_hosts` file. Configure only a host key whose fingerprint was verified out-of-band; changed keys must fail closed.
- Android app backup is disabled, and the FileProvider exposes only explicitly staged export/share directories.

**Credential rotation required:** previous revisions of this repository contained NVIDIA API tokens. Removing them from the current source does not remove them from Git history or old APKs. Revoke/rotate every previously committed token in the NVIDIA console and install a fresh key through Settings before using the LLM.

## Background automation

The current heartbeat is a private foreground service. The user must tap Start and grant notification permission where required; camera is a separate optional in-app action with its own runtime consent. After explicit opt-in, a boot receiver attempts to restart the service text-only. Each session can read two fixed public HTTPS trend pages, capture one still with Android's camera privacy indicator active, send the image and page text to NVIDIA's multimodal Nemotron endpoint, and create a local Markdown idea/project draft. Lucifer does not persist the image. The service cannot execute generated commands, deploy, publish, submit forms, contact people, spend money, or write outside app-private `files/heartbeat/`. A persistent notification provides Stop. It waits about 30 minutes after a session finishes; Android can defer or stop it, so exact timing is not guaranteed. An opted-in service attempts to resume after reboot or a sticky service restart in text-only mode; camera capture remains off until the user opens the app and explicitly re-enables camera access. Sessions incur model-provider usage and may cost money.

The **General** is a second user-started foreground service (`agents/android/TacticianService`). It has no execution path: it reads summaries and metadata the app already stores, writes a local brief, and records proposals that only the user can approve in the foreground. It cannot edit prompt files, change permissions, spend money, or contact anyone. Its model calls are capped per day with quiet hours, and its observation ledger is bounded and redacted. Prompt files (`soul.md`, `heartbeat.md`, every `agent-class.md`) are instructions that are sent to the model on each call, so writes reject obvious credentials; they are not a secret store, and the files are unencrypted in app-private storage. Sensing is limited to app-usage events Lucifer itself records — no message content, browsing history, or other apps' private data — and camera sensing is limited to a boolean that a session used the camera, never the image.

## Privileged backends

Lucifer can borrow two identities, and both are bounded on purpose.

**Shizuku** (ADB shell UID 2000, or root UID 0 if the owner started it that way) provides a *shell*.
Lucifer uses it for the terminal and for `pm`/`cmd`/`am` commands that the World panel builds. It is
not root by default, it does not survive a reboot, and the owner revokes it by stopping Shizuku or
removing the grant.

**Dhizuku** provides a borrowed *device-owner* Binder. It survives reboots and is the most powerful
capability in the app, so:

- Calls are limited to an allowlist of `IDevicePolicyManager` methods; anything else is refused before
  Binder is touched.
- Every state-changing call is confirmed by the owner against the literal operation being performed,
  one call at a time. There is no queue, no batch, no automation hook.
- Lucifer refuses — before any confirmation dialog — to hide, suspend, uninstall, or change the
  permission state of itself, Shizuku, Dhizuku, or Permission Manager X.
- Permission Manager X's own grants remain read-only for Lucifer, and terminal commands that mutate
  them are blocked by `TerminalCommandPolicy` regardless of backend.
- Owner calls are logged in a bounded in-memory ledger (label, backend, success, short note) so the
  owner can see what was done.
- `wipeData` is on the method allowlist for completeness but has no UI entry point and no caller.

If the owner instead provisions Lucifer directly (`dpm set-device-owner`), the same policy applies but
the calls go through public `DevicePolicyManager` APIs with no reflection and no hidden API.

The managed profile ("Lucifer's world") is containment for Lucifer's actions, not a security boundary
against the owner or against a compromised device owner. Cross-profile launches do not grant data
access to the owner's profile, and freezing the profile freezes whatever Lucifer started there.

Interactive browser automation and external website writes are not yet implemented. The user has selected a human-confirmation boundary before any external submission, post, message, or purchase. Preserve that boundary when building an automation engine; do not treat manifest declarations or prior camera consent as blanket consent for unrelated actions.

## Lean verification server

The verifier is the one component that accepts input from off-device, so its boundary is explicit.

- **No client can reach a shell.** Five tools exist; there is no tool that runs a file, an expression, or
  a command. `SourceGuard` additionally refuses `#eval`, `run_cmd`, `run_tac`, `run_elab`, `@[extern`,
  `@[implemented_by`, `unsafe`, `initialize`, `IO.FS`, `IO.Process`, `System.FilePath` and any
  `Lean.*`/`Lake.*` import in caller-supplied source, with a line number. Comments are stripped first so
  a legitimate author is not tripped by prose.
- **The screen is defence in depth, not the sandbox.** The remote host is expected to run unprivileged,
  without network, with a memory cap and a wall clock. Both layers are required.
- **The project root never comes from the wire.** A caller names a project id; only projects configured
  on the device exist. The job command pins `ROOT='<configured>'`, and `JobCommandGuard` refuses any
  command that does not carry the isolation, cleanup, timeout and marker skeleton the planner produces.
- **Keys are hashes.** `lvs_<id>_<secret>` tokens are shown once; only SHA-256 is stored, comparison is
  constant time, and revocation takes effect on the next request.
- **Refusals are free and inert.** Key/scope, tool allowlist, planning and source screening all happen
  before quota, and quota before the executor. Nothing is charged for a refusal, and nothing is charged
  when no host ran the job (`transport == "none"`).
- **The phone does not publish itself.** The listener binds loopback. A tunnel is a separate, visible,
  command-confirmed action; the Cloudflare token is read from a mode-600 file rather than passed as an
  argument, and the SSH form pins the host key.
- **A remote audit-vault is untrusted compute.** It receives a structured job (never a command), must
  echo the job id, and must attest the SHA-256 of the source it actually ran. A missing or wrong
  attestation is `ok = false` / `audit-vault-unverified`, not a pass; requests are HMAC-signed over
  `timestamp + body` with a phone-generated secret, and the phone refuses to sign for a public `http://`
  URL. An outage (`transport = "none"`) may fall back to the SSH backend; a verdict may not.
- **The gates cannot certify, and the type system says so.** `lean.gates` returns `GATES CLEAN —
  human validation still required` at best; there is no `VALIDATED` state, and a harness assertion
  fails if one appears. A customer key cannot buy a review, only the machine's objections.
- **No vault content crosses the boundary.** Findings, models, engagements and client names never enter
  the verifier. The ledger stores `keyId`, tool, credits, USD and success — never source text — so a
  client's proof is not retained after the call.
- **No payment credentials exist.** Metering records credits and dollars in a bounded in-memory-plus-file
  ledger; there is no processor, no card data, and no wallet signing anywhere in this path.

## Remaining hardening work

This app can execute shell commands by design. Treat command execution, fetched web content, and all external/model-provided content as untrusted. Before using it with valuable accounts or production infrastructure, device-test the SSH host-key pinning path and add a user-confirmation boundary for high-impact operations such as transaction signing (transaction sending is not currently exposed).

### Ordered NVIDIA credentials

Settings → NVIDIA API KEYS accepts a primary credential followed by comma- or
whitespace-separated fallbacks (up to 16 distinct keys). The order is preserved
in Android Keystore-backed storage; existing single-key settings still work.
An empty save clears the entire list. Invalid input rejects the entire edit.
The model client tries keys in this order within each model route. This is
failover, not a promise of availability or a way to bypass provider limits;
use only credentials you are authorized to use.

Local provisioning material belongs under ignored `phone_secrets/`, never in
source code, APK assets, PR text, or prompts. Local files are not automatically
installed on the phone: paste the ordered list into Settings on the device.
Do not share the local credential file. Its local copy is plaintext even though
the app's saved copy is encrypted. No live provider validation is implied by
recognizing a credential's format.
