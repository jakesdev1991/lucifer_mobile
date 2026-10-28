# Lean verification server

Lucifer hosts a Lean 4 proof-checking server in his world work profile and exposes it as a **Model
Context Protocol server he controls**. It has an owner (his key), customers (metered keys), and one
product: a verdict on a Lean proof.

This document describes what exists, what it refuses, what it costs, and what is still unverified.

## The split that makes it possible

A Pixel cannot build mathlib. Tens of gigabytes of RAM and hours of CPU do not appear because the
process is in a work profile, so the design is explicit:

| Where | What |
| --- | --- |
| **Lucifer's world (the phone)** | the MCP endpoint, keys, scopes, quota, the ledger, project configuration, the tunnel |
| **Remote Lean host** | every actual elaboration — `lake build`, `lean`, `#print axioms` |

The remote side can be **audit-vault** (an HTTP job API, preferred when linked and healthy) or the Debian
VM over SSH (the fallback). Both are behind one `JobExecutor` seam; keys, quota and billing never leave
the phone in either case. See [AUDIT_VAULT.md](AUDIT_VAULT.md).

The phone is the **control plane**. It never runs Lean, and it never accepts a command from a client.

## What this server is, and is not

It runs Lean and reports what the kernel says. That is the **floor**, not an audit: the adversarial
audit of the Omega corpus found 3 Critical, 8 High and 11 Medium issues that all typecheck perfectly —
`0 ≤ 0` under grand names, a Kepler equation true only because `M = 0` and `x / 0 = 0`, contradictory
definitions sharing one name. So `lean.gates` implements the four gates from
[`audit-vault`](AUDIT_VAULT.md), and the rest of the surface is honest plumbing around them.

**It never says "validated".** The best verdict is `GATES CLEAN — human validation still required`.

## The six tools

| Tool | Credits | Ceiling | What it does |
| --- | --- | --- | --- |
| `lean.check` | 1 | 120 s | Type-check a snippet or declaration against a project's imports; returns errors, sorries, axioms |
| `lean.sorry_scan` | 2 | 300 s | Count `sorry`/`admit`/`native_decide`/`axiom` so a proof cannot pass by cheating |
| `lean.axioms` | 3 | 300 s | `#print axioms` for named theorems — the check a reviewer actually asks for |
| `lean.build` | 5 | 900 s | `lake build <module>`, returns the log tail and failure count |
| `lean.gates` | 6 | 300 s | The four audit gates: placeholders, vacuity, name-vs-statement, declaration parity |
| `lean.verify` | 8 | 1800 s | Full build of a project or module with an axiom audit and a machine-readable verdict |

There is no shell tool, no "run this file" tool, and no `#eval`. That is the whole surface. `#eval` is
refused for the same reason a PoC runs on the auditor's machine and not here: it is code execution, and
the caller is not the auditor.

### The gates

| Gate | Blocker when |
| --- | --- |
| `PLACEHOLDERS` | any `sorry`, `admit`, `native_decide`, `sorryAx`, or `axiom` declaration — in the submission or the project |
| `VACUITY` | the statement is `True`, reflexive (`x = x`), closed (no variables — the `0 ≤ 0` pattern), has an unsatisfiable hypothesis, divides by a literal zero, or is about a quantity defined as `0`/`Unit` |
| `STATEMENT` | a grand name over a statement that cannot be false; a `_Stmt` alias suffix; two declarations sharing one name with different bodies |
| `PARITY` | a cited `theorem`/`lemma`/`def` is not where the claim says it is |

These are heuristics, not a decision procedure. Lean's kernel decides truth; nothing here decides
meaning. What it does is refuse to let a statement be *silently* worthless — with the declaration name,
the line, and the reason.

## The gate order

`tools/call` passes four gates, in this order, and every refusal is free:

1. **Key and scope.** `Authorization: Bearer lvs_<id>_<secret>`. Only the SHA-256 of the token is
   stored; comparison is constant time; revocation is immediate. Scopes are `READ`, `VERIFY`, `BUILD`,
   `ADMIN`.
2. **Tool allowlist.** Unknown tool names are `404 -32602`. `BUILD`, `VERIFY` and `SORRY_SCAN` need the
   `BUILD` scope in addition to `VERIFY`.
3. **Planning and source screening.** The project must be one configured locally (the root path never
   comes from the wire), the module must match `^(?:[A-Za-z0-9_]+)(?:\.[A-Za-z0-9_]+){0,8}$`, and
   caller-supplied source is screened by `SourceGuard`.
4. **Quota.** `402 -32002 QUOTA_EXCEEDED` when the key cannot pay. Credits are enforced; dollars are
   recorded.

A refusal never runs anything and never charges. Work is **charged after it happens**, so a crash loses
revenue rather than billing for a proof nobody checked; a job that never reached a host
(`transport == "none"`) is never billed at all.

### Source screening

`SourceGuard` rejects, with a line number: `#eval`, `run_cmd`, `run_tac`, `run_elab`, `@[extern`,
`@[implemented_by`, `unsafe`, `initialize`, `IO.FS`, `IO.Process`, `System.FilePath`, and any
`Lean.*`/`Lake.*` import. Comments are stripped before screening so a comment cannot trip it.

This is **defence in depth, not the sandbox**. The remote executor is still expected to run
unprivileged, without network, with a memory cap and a wall clock. Both layers are required; a text
screen can always be argued with.

## Plans and money

| Plan | Credits / 30 days | $/credit |
| --- | --- | --- |
| `INTERNAL` (Lucifer's own) | unbounded | 0 |
| `FREE` | 50 | 0 |
| `METERED` | 5 000 | 0.012 |
| `ENTERPRISE` | 200 000 | 0.008 |

No payment processor is wired. The server **enforces quota** — it refuses work it cannot bill for — and
the ledger records what each key consumed (bounded to the last 500 lines) so a processor can be
attached later without renegotiating semantics.

Lucifer's internal key is what he uses for **bug-bounty reproduction**, **workflow verification**, and
**the theory-of-everything proofs**. It is never blocked and never billed.

`lean.gates` is what he runs before calling something a finding: a `BLOCKED` report means it is not
ready, and no amount of billing changes that.

He does not need the token to use it. `VerifierRuntime.ownerCall` resolves his internal key record
directly, so the owner path skips authentication but not planning, source screening, quota or the
executor — `VerifierController.ownerTool(...)` and `ownerCheck(...)` are the entry points a workflow or
a bounty run would use. That is also why the internal token can stay shown-once: nothing has to keep it
around to call in.

## Keys

- Format `lvs_<key id>_<24-char secret>`, ids `k` + 8 characters.
- Only `SHA-256(token)` is written, through `SecretStore` (AES-GCM under an Android Keystore key).
- Tokens are shown exactly once, in the World panel, and never again.
- Any service the owner writes for something else. Never reuse a customer key for Lucifer's own work.

## Exposure

The socket binds `127.0.0.1:8791`. It does not open a port to the internet by itself.

- **Cloudflare tunnel** (preferred): `ExposurePlan.cloudflare(hostname)` returns a command that reads
  the token from `~/.lucifer/verifier/tunnel.token` (mode 600, directory 700) instead of taking
  `--token` on the command line, so the token does not land in `ps`, shell history, or a log line, and
  forwards to `http://127.0.0.1:8791`.
- **SSH reverse tunnel**: `ExposurePlan.sshReverse(host)` pins the host key
  (`StrictHostKeyChecking=yes`, `ExitOnForwardFailure=yes`).
- **Loopback only**: the default. Clients on the device, or an ADB forward, still work.

Starting a tunnel is a **visible terminal action** with the exact command confirmed by the owner, like
every other state-changing action in this project. The panel shows the command; it does not run it.

## Running it

1. On the Lean host (a Debian VM, a container, or a cloud box): clone the project, `lake exe cache get`,
   `lake build` once so the artifact cache is warm, and place it at the path configured for `toe`
   (default `/srv/lean/Omega_Theory_Everything`).
2. In Lucifer: Settings → VM host, port, and SSH password; save a verified `known_hosts` entry. The
   verifier reuses that exact strict SSH path (`TerminalExecutor.executeVmDirect`) with the job's own
   timeout instead of the interactive 30 s ceiling.
3. World tab → **START SERVER**. A notification appears with the port. The runtime is a foreground
   service (`dataSync`); it stops when the owner stops it.
4. World tab → **MY KEY** for Lucifer's internal key, or **FREE KEY** / **PAID KEY** for a customer.
   Copy the token the moment it appears; it is not stored.
5. Point a client at `http://127.0.0.1:8791/mcp` (or the tunnel hostname):

```json
{
  "mcpServers": {
    "lucifer-lean-verifier": {
      "type": "http",
      "url": "https://verifier.example.com/mcp",
      "headers": { "Authorization": "Bearer lvs_k12345678_xxxxxxxxxxxxxxxxxxxxxxxx" }
    }
  }
}
```

`GET /health` returns the server name, protocol version, configured project ids, active key count and
uptime — no key material, no token, no ledger lines.

## What the harness proves

`./scripts/verify_verification_core.sh` runs two harnesses plus an adapter type-check:

**Control plane (32 checks)** — the handshake is free while `tools/list` needs a key; exactly five tools
with non-destructive annotations; unknown methods and tools refused; scopes gate expensive tools; a key
that cannot pay is refused before anything runs; correct single charge; internal key unbounded; period
roll keeps lifetime; USD per plan; ledger bounded at 500 lines; key issue/verify/revoke and strict
header parsing; six source-screen refusals with line numbers; comments do not trip it; size/NUL limits;
placeholder counting across all four apologies; **nine gate checks** covering the three corpus patterns
(`0 ≤ 0` under a grand name, a degenerate `M := 0` with `x / 0`, two definitions sharing one name), the
`_Stmt` evasion, parity, and the assertion that a clean run never emits `VALIDATED`; plan refusals (unknown project, module injection, missing source, axioms without
declarations, source on `build`); timeout clamping both ways; the command is bounded, cleaned up, and
contains no raw source; per-tool command shapes; base64 round-trip including multibyte text; result
parsing; project registry validation; the tunnel token never on the command line.

**Runtime and transport (23 checks)** — keys persist as hashes only and survive reload; a corrupt key
file resurrects nothing; ledger/project codecs round-trip and reject nonsense; the handshake is free
and everything else needs a key; a metered key is charged once and the meter and ledger survive a
restart; a job that never reached a host is not billed; refused requests and exhausted quota never
touch the host; a revoked key is refused everywhere; the internal key is unbounded and free; the
command tripwire accepts every planned command and refuses tampered ones (missing timeout, no temp
dir, no cleanup, unpinned root); a **real loopback socket** serves `/mcp` and `/health`, refuses
unknown paths with 404 and `GET /mcp` with 405, and refuses an oversized body with 413 before reading
it; framing rejects junk and chunked bodies; the panel state never carries a token it was not handed;
Lucifer can call his own server through the same gates without presenting a token; and the audit-vault
contract is signed, parsed and distrusted correctly (see [AUDIT_VAULT.md](AUDIT_VAULT.md)).

**Adapter type-check** — `VerificationService`, `VerifierController`, `VmJobExecutor` and the runtime
compile against stubs of the Android surface they use.

## What is not verified

- No Gradle build, no APK, no device run in this environment. The service, the notification, the SSH
  transport and the Compose panel are **implemented, not device-tested**.
- The SSH transport has not been exercised against a real Lean host through this path; the job command
  shapes are pinned by tests, the network round trip is not.
- Cloudflare tunnel setup is documented, not provisioned: there is no tunnel token in this repository
  and none should be committed.
- The remote host's own sandboxing (user, network namespace, memory cap) is the operator's
  responsibility today. `SourceGuard` is the app-side half.
- Billing is metering only. There is no processor, no invoice, no refund path.

## Files

| Path | Role |
| --- | --- |
| `verification/VerificationCore.kt` | tools, projects, source screen, keys, billing, planner, result parser, MCP protocol, exposure, charter |
| `verification/VerificationRuntime.kt` | persisted keys/meters/ledger/projects, the single request door, UI state |
| `verification/VerificationServer.kt` | HTTP framing, loopback listener, endpoint routing, command tripwire |
| `verification/VerifierController.kt` | owner-facing facade: start/stop, issue/revoke, project root, exposure command |
| `verification/VerificationService.kt` | foreground service holding the socket |
| `verification/VmJobExecutor.kt` | the SSH transport for planned jobs |
| `verification/StatementGates.kt` | the four gates: declaration parsing, vacuity heuristics, overclaim, duplicates, parity |
| `verification/AuditVault.kt` | an optional remote Lean-host contract (HMAC-signed); audit-vault itself is a private audit workspace, not a job runner |
| `ui/components/VerifierCard.kt` | the World-tab panel |
| `tools/verification-core/` | the two harnesses and the Android stubs |
| `scripts/verify_verification_core.sh` | runs all of it |
