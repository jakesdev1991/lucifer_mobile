# Lucifer repository-wide opportunity review

**Review date:** 2026-10-27
**Branch:** `arena/5cd079cb-lucifer-mobile`
**Method:** Static inventory and source review of the active Android app, documentation, scripts, native code, retrieval/memory stack, and dormant modules. This is not a build, penetration test, scientific review of the Omega corpus, or device validation.

## Executive summary

Lucifer does not mainly need more named AI subsystems. It already has a useful set of practical primitives: natural-language routing, a multi-target terminal, an interactive Termux PTY with chat overlay, local static/API website staging, a user-led task runner, SQLite/FTS episodic memory, a Termux retrieval service, Pixel diagnostics, and an on-device Lean workflow. The largest missed opportunity is to **compose those dependable pieces into tested, evidence-backed workflows**.

The recommended sequence is:

1. **Make the Android app build and test reproducibly.** This repository has 69 active Android Kotlin files, no `test`/`androidTest` source directories, no GitHub Actions workflow files, and no Java/Kotlin toolchain in this workspace. Until that changes, feature additions cannot be checked for basic compilation or regressions.
2. **Make knowledge retrieval canonical and attributable.** The app now passes retrieved Omega text to the reasoning council as untrusted context, but its GitHub overlay still tracks mutable `main` and loses source-path/revision provenance. `audit-vault` remains inaccessible to the current GitHub credential (403), so it cannot yet be indexed.
3. **Finish the memory lifecycle the user selected.** Local retention is no longer age-based and is importance-ranked, but low-value compaction and summary-only long-term offload are not implemented. At the 2 GiB cap, new writes may fail rather than being compacted or safely archived.
4. **Turn the existing runner into useful project work.** It can guide a user through one step at a time, but it does not directly orchestrate most panels, build/test workflows, or a repository-specific task plan. Typed, permission-bounded adapters would make it useful without granting an unrestricted agent loop.
5. **Make the bug-bounty workflow evidence-rich before adding live tests.** There is now a fixed checklist, but no structured program policy, enforced asset allowlist, scanner, or program integration. Use the canonical corpus and user-provided scope before live testing; keep report submission confirmation-gated.

## Current strengths worth building on

- **User-controlled execution:** natural-language requests are separated into chat, clarification, panel navigation, and terminal actions; non-read-only shell work requires confirmation. Confirmation is not a sandbox, but it is a useful approval point.
- **Useful terminal loop:** the Termux PTY, tmux persistence, floating chat, and Android Linux VM SSH path give Lucifer a realistic place to inspect projects and run tools.
- **Local-first workspace milestones:** static-site and API prototypes stage locally and bind to loopback; that is a good foundation for the user's first website milestone without premature publishing.
- **Memory and retrieval primitives:** local SQLite/FTS retrieval and the C++ Termux MCP server provide a clear separation between private Drive reference memory and app-private episodes.
- **Evidence-aware workflows:** the task runner stores bounded observations, uses a fixed target, and requires user confirmation to complete. The bug-bounty starter keeps authorization, source provenance, and report submission visible.
- **Formal verification direction:** the two-phase Lean VM workflow binds work to a repository SHA/toolchain rather than treating a CI badge or theorem filename as proof.

## Highest-value opportunities

| Priority | Opportunity | Missed value today | Practical next step |
|---|---|---|---|
| P0 | Reproducible Android build and tests | The main product surface has no verified compile or test gate; this makes every other improvement higher risk. | Add a JDK 17/Android SDK CI build for debug and release, lint, manifest checks, and unit tests. Start with pure Kotlin tests for workflow transitions, memory scoring/migrations, and command policy. |
| P1 | Canonical knowledge desk for Omega + audit-vault | Search can surface useful research, but mutable `main`, flattened paths, and missing commit/blob citations make results hard to trust or reproduce. Audit-vault is not currently reachable. | Pin each sync to a resolved commit, retain original paths/blob IDs/line ranges, label source and commit in every hit, and keep generated summaries separate from canonical text. Retry audit-vault only after repository read access is granted. |
| P1 | Memory compaction and long-term archive | Importance ranking helps retrieval, but there is no automatic low-value compaction or archive outbox; the local DB can grow until it rejects writes. | Implement a redacted, idempotent summary-only archive queue to a **separate** long-term destination; never write generated memories into the owner-curated `Lucifer_Memory.md`. Keep retries visible, retain failed uploads locally, and support review/clear. |
| P1 | Personal project co-worker | Lucifer can run terminal steps, but task completion depends on manual observations and the runner does not own the complete inspect → edit → test → diff workflow. | Add typed project actions for status, file inspection, test execution, diff review, and artifact summaries. Preserve exact target display and confirmation for writes. First ship a repo-health workflow that produces test results and an actionable handoff. |
| P1 | Bug-bounty analyst | Current blueprint is a process checklist, not an enforced program-scope or vulnerability-management system. | Once policy/scope is available, store program rules and exact inclusions/exclusions; add offline repository review, finding deduplication, evidence redaction, provenance, severity rationale, and report drafts. Do not add active target scanning until a scope gate is enforced and test behavior is explicitly authorized. |
| P1 | Model data and cost controls | Chat, memory context, camera, browser text, heartbeat sessions, and workflow data can reach configured model providers; recurring heartbeat work has no clear user-facing budget/retention ceiling. | Add per-feature egress controls (including local-only mode), a visible daily token/cost/session budget, camera-send preview/consent state, heartbeat artifact retention/size caps, and a one-tap stop/clear experience. |
| P2 | Make project automation observable | Current task records capture bounded text but do not provide strong artifact provenance, test-duration trends, or a durable job/event ledger. | Add structured task events (start, exact command/target, exit code, duration, commit, user approval) with redaction and explicit retention; link each finding/report to evidence IDs. Do not reuse the current prototype Evidence Ledger as a security boundary. |
| P2 | Finish local website tooling | Static/API staging is a good milestone, but there is no guided content management, local data persistence, or tested handoff/deployment path. | Add template-specific validation, backup-before-overwrite, preview health checks, and a deployment checklist. Keep public publishing as a separate approved action. |
| P2 | Reduce dormant-code risk | Many architecture-sounding modules are prototype or unreferenced code, and legacy Kai helpers expose dangerous primitives if reconnected. This obscures what Lucifer really can do. | Maintain an active-vs-dormant registry. Either test and integrate a module behind a narrow interface or label/archive it. Do not connect the Kai browser/wallet/sandbox Python helpers without replacing unsafe shell, file, and credential handling. |
| P2 | Remove build ambiguity | `native/CMakeLists.txt` references the wrong source/header paths and is not wired into the Android Gradle app; `commonMain`, `kotlin.backup`, and `.bak` files imply architectures that are not active. | Decide whether native JNI is intentionally abandoned. If not, repair NDK packaging and add ABI smoke tests; otherwise clearly deprecate it. Clean up stale source only after confirming no external tooling consumes it. |

## Concrete workflows that could make Lucifer more useful

### 1. “Take this project from issue to verified patch”

Inspect repo/worktree → identify relevant code and tests → plan a small patch → edit only the agreed files → run focused tests → inspect the complete diff → generate a handoff with commit, test output, and known risks. Today, pieces exist in the terminal and task runner, but the flow is not a single tested capability. Keep edits in the selected LOCAL/Termux/VM workspace and ask before destructive commands or external writes.

### 2. “Research this claim against canonical sources”

Search Omega Theory and audit-vault independently → show exact repository, commit, file, and line → keep source text separate from explanation → distinguish “source says” from “evidence supports” → return uncertainty and contradictory evidence. The recent ToE change correctly makes retrieved passages untrusted context; the next step is immutable provenance and claim-level citations.

### 3. “Prepare a bounty report without touching out-of-scope systems”

Load the program policy and exact scope → default to static/local review → record evidence and counter-evidence → redact secrets/PII → draft the report → require explicit confirmation for any live test or submission. The present workflow is a useful template, but is not yet a technical network/asset sandbox.

### 4. “Keep my projects continuous across sessions”

Persist compact importance-ranked decisions/findings locally → deduplicate and merge related episodes → queue only selected summaries to a separate private long-term server → keep the source of truth and audit-vault corpus canonical. Current local memory is disk-backed but does not yet compact/offload automatically.

### 5. “Build, verify, and preview locally”

Use Termux or the Debian VM for code, local website staging, tests, and the selected on-device Lean checks. Capture build ID, tool versions, test exit codes, and preview health. Keep the preview loopback-only and make publishing an explicit separate milestone.

## Dormant or misleading code: avoid connecting until repaired

- **`ContextCompressor.kt`:** contains pseudo/hash embeddings, an in-memory placeholder vector store, and a stub NVIDIA client; its only discovered caller is a backup MainActivity. It is not a usable production memory/compaction engine.
- **`EvidenceLedger.kt` and cognitive modules:** the existing adversarial review identifies broken Merkle/provenance semantics and heuristic/demo behavior. They are not currently the active safety policy or operational audit record.
- **APPA, QSTAR, RCOD, GEPA, ATOMS, and TARO-style modules:** source search found no active production wiring for the reviewed prototypes; some contain arbitrary constants or fabricated performance claims. Treat them as experiments, not routing policy or proof.
- **Legacy Kai scripts and JNI:** the Python helpers include shell injection, arbitrary file operations, browser automation, and credential-handling hazards if reconnected. The separate JNI build also has path errors and is not included by Gradle. Keep these isolated or retire them; do not use them as shortcuts for missing safe tools.
- **Stale `kotlin.backup`, `.bak`, and `commonMain` trees:** they raise maintenance and audit cost and can mislead future work about which UI/runtime is shipped. Reconcile them after a dependency/usage check.

## Review limits and immediate follow-up

- Inventory found 291 non-build repository files, 69 active Android Kotlin source files, 125 ToE assets, zero app test source directories, and zero GitHub workflow files.
- `java` and `kotlinc` are absent in this workspace, so I could not compile or run Android code. The prior native C++ MCP tests cover its protocol paths, not the Android application.
- GitHub CLI is authenticated as `jakesdev1991`, but `git ls-remote` for `jakesdev1991/audit-vault` still returns HTTP 403 (“Write access to repository not granted”). No repo content was read in this review.
- The Omega Theory corpus and its scientific claims were not re-audited. This is a source/usefulness review, not a scientific or Lean verification.
- The earlier `ADVERSARIAL_REVIEW.md` remains the detailed security finding ledger; its ToE finding A-04 is now marked partially remediated because retrieved evidence reaches the reasoning council, while provenance/canonical sync gaps remain.

**Recommended first milestone:** CI build + four pure logic test suites (workflow transitions, memory migration/scoring, terminal command policy, and source provenance/parser). Then ship immutable Omega/audit-vault indexing when access is available, and complete summary-only memory offload as a separate write-capability change.
