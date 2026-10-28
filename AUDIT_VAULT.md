# audit-vault, and what the verifier is actually for

`audit-vault` is a **private audit workspace**: engagements, models, findings, PoCs, and the Lean 4
corpus that proves the method. It is not a compute service, it does not execute Lean, and nothing in
Lucifer's phone writes to it. Everything there is confidential until Jacob ships it.

That reframes the server. The honest description of what I built before reading your repo was
"a Lean build service with a billing meter" — which sells the thing your own corpus audit says is the
floor:

> Kernel soundness ("nothing proved is false") is the floor, not the audit. The adversarial audit's
> real catches were **statement-level** dishonesty.

3 Critical, 8 High, 11 Medium, ~10 Low, every one of which typechecks perfectly. A server that answers
"does it compile, and does it use `sorry`" answers a question you have already established is
insufficient. So the tool surface changed.

## What the server now checks: the four gates

| Gate | Check | Equivalent in the vault |
| --- | --- | --- |
| `PLACEHOLDERS` | zero `sorry` / `admit` / `native_decide` / `axiom`, project-wide and in the submission | `audit_axioms.py --max 0` |
| `VACUITY` | no vacuous, closed, unsatisfiable-hypothesis, decimal-degenerate, or `x / 0` statement | the vacuity audit |
| `STATEMENT` | the name asserts no more than the statement proves; duplicate names with different bodies are a blocker | the statement audit, `bridge_*` renaming, the `_Stmt` evasion |
| `PARITY` | a cited theorem exists where it is claimed | declaration parity |

`lean.gates` (6 credits, 300 s) runs all four and returns a report, structured and in prose:

```json
{
  "status": "BLOCKED",
  "findingStatus": "UNVALIDATED (blocked by gates)",
  "blockers": 1,
  "reviews": 0,
  "suspects": [
    {
      "gate": "STATEMENT",
      "severity": "BLOCKER",
      "line": 1,
      "declaration": "TheoryOfEverything",
      "detail": "this is the corpus pattern: a grand name over a statement that cannot be false. A valid proof of a worthless statement."
    }
  ]
}
```

Three concrete corpus cases it now reproduces in the harness:

- `theorem TheoryOfEverything : 0 ≤ 0` → vacuity + overclaim → **BLOCKED**.
- `def M : ℝ := 0` plus a Kepler equation over `M` → degenerate model, and a literal `x / 0 = 0` →
  **BLOCKED**.
- `def profile := √(πΦ+1)` and `def profile := ℓP0·√(1−Φ²)` in one file → **BLOCKED**, "two things
  share one name".

## The one rule in the types

**Nothing here can say "validated".** The best a clean run produces is:

```
GATES CLEAN — human validation still required
findingStatus: GATED — awaiting human validation
```

There is no branch that emits `VALIDATED`, and a harness assertion fails the build if one appears. That
is your L3/L4 rule encoded rather than documented: AI runs at full speed, Jacob's reproduction is the
gate, and a machine we wrote cannot stand in for it — least of all a machine a customer's key can call.

## The `#eval` boundary

Your PoC step is "a `#check` / `#eval` / test that the theorem typechecks and the counterexample
fails". The server **refuses** `#eval`, `run_cmd`, `run_tac`, `unsafe`, `@[extern]` and tooling imports
in caller-supplied source.

That is not a disagreement with the workflow, it is the boundary between the two machines. A PoC with
`#eval` runs on Jacob's box, against a repository he chose, as part of validation. A server that
accepts arbitrary customer source over the internet cannot run it, because `#eval` is code execution
and the customer is not Jacob. PoCs stay in `poc/`; the server only ever sees proof text.

## audit-vault is not a job executor

Earlier in this branch I wrote a signed `POST /v1/jobs` contract and an `ExecutorRouter` that treats
"audit-vault" as the primary Lean backend. Nothing in your repo suggests audit-vault accepts jobs, so
that was a guess dressed as an integration. What it is now:

- `AuditVault.kt` stays, but it is **an optional remote Lean host contract**, useful if you ever want a
  second box elaborating proofs at speed. It describes any HTTP job runner, not audit-vault.
- The phone works without it. Default backend: the Debian VM over SSH, exactly as before.
- Nothing sends vault contents anywhere. No finding, no model, no engagement text, no client name
  crosses this boundary in either direction. The ledger stores `keyId, tool, credits, usd, ok` and
  never source — a client's proof text is not retained, which is also what keeps the vault's
  confidentiality rule intact.

## Where the phone server actually helps the vault

1. **Bounty reproduction, at speed.** `VerifierController.ownerGates(...)` runs the four gates on a
   statement before it is allowed to be called a finding. Lucifer's internal key is free and needs no
   token.
2. **Customer-facing evidence.** A `$750` engagement can hand the client a gate report per finding:
   blockers named by line, with the reason. That is more defensible than "trust me", and it is
   deliberately not a certification.
3. **The corpus regression.** Run `lean.gates` over `audits/lean-corpus/` as a CI step: any new
   `0 ≤ 0`-shaped theorem fails the build before it reaches a public claim.
4. **Shipping discipline.** `ship.py` needs `--confirm` plus a per-file manifest. The server adds a
   precondition it can enforce: a finding whose gate report is `BLOCKED` is not eligible to ship. The
   phone never pushes anything; it only refuses to call it ready.

## What it still cannot do

- **It does not read your vault.** No filesystem, no git, no repo sync. The caller supplies the
  statement text; parity is checked against the host it runs on.
- **It cannot judge meaning.** "Does this statement mean what its name claims" is a judgment call. The
  gates surface the *shape* of the dishonesty — closed statements, degenerate definitions, grand names
  over `0 = 0` — and put a line number in front of a human. Everything else is still your reading.
- **Cross-file contradictions** are only caught within one submitted source. Two definitions in
  different modules sharing a name need the project scan, which the remote grep does not resolve
  semantically.
- **It is not a certificate.** No customer gets to point at this and say "machine-checked audit". The
  status strings refuse that reading, in the field a client will actually look at.

## Files

| Path | Role |
| --- | --- |
| `verification/StatementGates.kt` | the four gates: parsing, vacuity heuristics, overclaim, duplicate names, parity |
| `verification/VerificationCore.kt` | `lean.gates` tool, remote grep for placeholders and parity, gate report in `structuredContent` |
| `verification/AuditVault.kt` | optional remote-host contract (HMAC-signed), **not** an audit-vault integration |
| `tools/verification-core/VerificationCoreTests.kt` | 9 gate tests, including the three corpus patterns |
