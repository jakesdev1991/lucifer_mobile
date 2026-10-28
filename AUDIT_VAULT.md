# audit-vault integration contract (v1)

Lucifer's phone hosts the MCP endpoint; **audit-vault** is the compute backend behind it. This file is
the contract between them — written before I have read your repo, so treat it as a proposal to reconcile,
not a fait accompli. If audit-vault already has an API, this file is what changes; the phone's keys,
scopes, quota and ledger do not.

**Status: proposed, implemented on the phone side, unverified against a real audit-vault.**

## Who owns what

| | Phone (Lucifer's world) | audit-vault |
| --- | --- | --- |
| MCP protocol, `/mcp`, `/health` | owns | — |
| Customer keys, scopes, revocation | owns | never sees them |
| Quota enforcement, 402 | owns | — |
| Ledger, credits, dollars | owns | — |
| Source screening, project registry | owns | may re-screen, must not rely on it |
| Running Lean, log capture, axioms | — | owns |
| Sandboxing the run (user, netns, memory, wall clock) | — | owns |
| Attestation of what it ran | verifies | produces |

audit-vault is **untrusted compute**. It gets a structured job, not a shell string, and the phone
recomputes the verdict from the primitives it returns instead of believing an `ok: true`.

## Endpoints

### `GET /v1/health`

```json
{
  "contract": 1,
  "name": "audit-vault",
  "version": "0.3.1",
  "tools": ["lean.check", "lean.build", "lean.sorry_scan", "lean.axioms", "lean.verify"],
  "max_timeout_seconds": 1800
}
```

`tools` empty means "everything the contract names". The phone probes this at most once per 30 s with a
4 s timeout, and falls back to the Debian VM when it fails.

### `POST /v1/jobs`

Headers:

```
Content-Type: application/json
X-Lucifer-Contract: 1
X-Lucifer-Job: job-ab12cd34          # idempotency: same id must not run twice
X-Lucifer-Timestamp: 1761692400000   # ms since epoch
X-Lucifer-Signature: <hex HMAC-SHA256>
```

Signature: `HMAC-SHA256(shared_secret, "<timestamp>\n<body>")`, hex, lowercase. Reject a timestamp more
than **300 s** off your clock; that is the whole replay defence, so keep your clock disciplined.

Body:

```json
{
  "contract": 1,
  "job": "job-ab12cd34",
  "tool": "lean.check",
  "timeout_seconds": 120,
  "project": {
    "id": "toe",
    "root": "/srv/lean/Omega_Theory_Everything",
    "module": "ToE",
    "toolchain": null
  },
  "payload": {
    "module": "ToE",
    "declarations": [],
    "source_b64": "dGhlb3JlbSBsdWNpZmVyIDogVHJ1ZSA6PSB0cml2aWFs"
  }
}
```

`source_b64` is present only for `lean.check`. Everything else acts on the configured project. The vault
builds its own command line from these fields; **the phone will never send a command**, and a vault that
concatenates `root` into a shell word is the bug, not the input.

### Response `200`

```json
{
  "contract": 1,
  "job": "job-ab12cd34",
  "ok": true,
  "exit": 0,
  "output": ["Check.lean:3:0: no goals", "..."],
  "errors": [],
  "sorry_count": 0,
  "axioms": ["ToE.main' depends on axioms: [propext, Classical.choice, Quot.sound]"],
  "elapsed_ms": 4210,
  "attestation": {
    "source_sha256": "9f2c…",
    "command_sha256": "31ab…",
    "toolchain": "leanprover/lean4:v4.9.0",
    "host": "vault-1"
  }
}
```

Rules the phone enforces on the reply:

| Check | On failure |
| --- | --- |
| `contract == 1` | reply discarded, `transport = "none"`, fallback runs |
| `job` equals the id it sent (or omitted) | reply discarded |
| `attestation.source_sha256` equals SHA-256 of the source it sent | **unverified**: `ok = false`, `transport = "audit-vault-unverified"` |
| `ok` consistent with `exit == 0 && errors.isEmpty()` | phone recomputes; a disagreement is recorded as an error |
| `output` ≤ 400 lines, `errors` ≤ 100, `axioms` ≤ 50 | clamped |

A **verdict** is `transport = "audit-vault"` even when the proof fails — that is the answer. Only
`transport = "none"` means "did not answer", which is the sole case where the phone retries elsewhere.
Non-`200` responses, unreachable hosts and contract mismatches are all outages, never verdicts.

### Suggested error codes

```
400  malformed body / unknown contract
401  missing or bad signature, stale timestamp
409  job id already running
413  body over 400 KB
422  project unknown to this vault, or payload refused
429  this vault is at its concurrency limit
500  internal failure (the phone treats it as an outage)
```

## Implementing it (any language)

1. Read `X-Lucifer-Timestamp` and `X-Lucifer-Signature`; recompute over the **raw body bytes**; compare
   in constant time; reject skew > 300 s.
2. Look up the project by `id` in your own config. Never trust `root` blindly — allowlist it.
3. Decode `source_b64` into a temp dir you create and delete; run with `timeout`, unprivileged, no
   network, memory-capped.
4. Return the primitives, not a verdict you expect to be believed. The phone decides.
5. Echo `job`. Derive `attestation.source_sha256` from the bytes you actually wrote to disk.
6. Store nothing about the caller: no keys, no customer identity, no quota. You are compute.

## Phone-side fallback

```
                ┌──────────────► audit-vault (healthy)  ──► verdict
job ──► router ─┤
                └──────────────► Debian VM (SSH)        ──► verdict
```

- Primary chosen only when linked **and** `/v1/health` passes its cached probe.
- `transport = "none"` from the primary ⇒ fallback runs, and the result's first line says why.
- A verdict — pass *or* fail — is never retried elsewhere.
- No fallback configured ⇒ the call fails honestly rather than reporting a proof nobody checked.

The phone generates the shared secret when you link a vault URL in the World tab, shows it once (
`SecretStore`-backed, never in the ledger or the health JSON), and refuses to sign for a public `http://`
URL — only `https://`, or plain HTTP on loopback/RFC1918.

## Open questions when you drop the repo in

1. Does audit-vault already have an HTTP surface, or is it a CLI/service on the Lean host? If it is a
   CLI, the adapter becomes "run this on the box" and the contract above is unnecessary.
2. Is attestation already in its output shape (digests, host, toolchain) or do I add it?
3. Does it already speak MCP? If so the phone should *proxy* its tools and my five-tool surface goes
   away — one less vocabulary to keep in sync.
4. Does it keep its own audit log? If you want that as a second book of record, it stays read-only for
   billing; the phone's ledger remains the billing authority.

## Files

| Path | Role |
| --- | --- |
| `verification/AuditVault.kt` | contract, HMAC signing, HTTP transport, `AuditVaultExecutor`, `ExecutorRouter`, `HealthProbe` |
| `verification/VerifierController.kt` | link/unlink, secret generation, backend status |
| `ui/components/VerifierCard.kt` | URL field, link/unlink, one-time secret, backend line |
| `tools/verification-core/VerificationServerTests.kt` | 8 of the 23 runtime checks cover this contract |
