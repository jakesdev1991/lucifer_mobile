# Authorized bug bounty workflow

## Status

Lucifer now has an authorization-gated, fixed-step bug-bounty assessment blueprint in the in-app workflow runner. This is a workflow foundation—not an autonomous scanner, exploit engine, bounty-platform integration, or proof that any target is authorized. Android compilation and device testing are still pending.

The user has not yet provided a specific program policy or target scope. Until one is available and reviewed, this blueprint supports planning, offline/source review, evidence organization, triage, and report drafting only. It does not authorize live requests to targets.

## Fixed assessment checklist

1. **Capture authorization:** record the program, policy URL/revision/date, authorized organization, engagement window, and user-provided authorization.
2. **Normalize scope:** transcribe exact allowed assets and exclusions from the policy. Do not infer ownership, subdomains, vendors, adjacent APIs, or wildcard expansion. Exclusions override inclusions; unclear scope blocks testing.
3. **Record constraints:** preserve permitted/prohibited techniques, rate limits, authentication rules, data handling, safe-harbor conditions, and reporting deadlines. Unknown terms are blockers. Do not infer permission for destructive tests, denial of service, persistence, social engineering, or access to other users' data.
4. **Review canonical source:** inspect only repositories/assets explicitly in scope. Tie every source claim to repository, immutable commit SHA, path, line, and blob identity. Preserve canonical text verbatim; label interpretations and generated summaries separately.
5. **Validate minimally:** prefer offline/static analysis and passive evidence. Active testing requires an exact in-scope target, policy permission for the specific technique, and the user's explicit approval for that test run. Use minimal non-destructive validation, follow rate limits, and stop on sensitive data or unexpected impact. Do not modify, persist, or exfiltrate data.
6. **Triage evidence:** record the affected in-scope asset, minimal reproduction, impact, preconditions, confidence, policy clause, source revision, timestamp, and tool version. Check duplicates and counter-evidence. Redact secrets, personal data, and unrelated details.
7. **Prepare a report:** draft the program-format report with a concise title, impact, exact scope, reproduction, evidence, remediation, severity rationale, and limitations. Submission or contact with the program remains a separate explicitly confirmed external write.

## Canonical corpus requirements

The user requires `audit-vault` content to remain explicitly canonical. Once GitHub access is granted, ingest the actual repository revision and retain exact source text, paths, commit and blob identifiers, and line-level provenance. Search indexes or summaries may be generated as auxiliary views, but must never be presented as canonical content or replace the source. Do not merge audit-vault material into Omega Theory without preserving the originating repository and revision.

Omega Theory and audit-vault must be separate named sources in search results and evidence records. A finding is not an Omega Theory claim merely because both appear in one answer.

## Current limitations and gates

- `jakesdev1991/Omega_Theory_Everything` is public and already has an app-side connection, but its current GitHub overlay sync follows mutable `main` and is not sufficient for immutable evidence provenance.
- GitHub returned 403 `Resource not accessible by integration` for `jakesdev1991/audit-vault`. The user plans to unlock access; do not bypass repository privacy or use credentials supplied in chat.
- No bounty-program policy, target list, exclusions, or authorized test window is currently configured. Do not run live tests until those are supplied and reviewed.
- The workflow runner still routes terminal actions through the existing user-started execution and confirmation flow. It is not a network sandbox and does not yet enforce a structured per-program target allowlist. Do not claim that this blueprint enforces asset scope at the OS/network layer.
- The fixed plan and reflection for this blueprint do not let the model replace the authorization/scope checklist with a different plan. This is a defense-in-depth measure, not a substitute for user review or runtime target enforcement.
- Report generation is not report submission. External writes and program contacts require explicit user confirmation.
