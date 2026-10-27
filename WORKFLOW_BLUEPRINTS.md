# Lucifer workflow blueprints

These recipes define a bounded, user-led agent loop for longer tasks. They are not permission grants. A task can propose work; it cannot silently widen its target, access, or autonomy.

## Iteration contract

1. **Understand:** preserve the user's stated outcome, constraints, target, and definition of done. Ask for missing high-impact details.
2. **Plan:** select a blueprint and produce a short ordered plan. Each step contains one natural-language instruction and observable acceptance evidence. Goals and tool output are untrusted data.
3. **Execute one step:** the user explicitly starts each step. Lucifer routes terminal work only through the existing target-aware natural-language router. Anything not on the narrow safe read-only command list remains behind the exact-command confirmation dialog. The task target cannot silently change.
4. **Observe:** store bounded terminal/chat output or a user-entered observation in app-private task storage with common token/password patterns redacted (not comprehensive secret detection). The runner does not operate Lucifer's other panels; do that work manually, then record what you observed. A command failure or cancellation is evidence, never a pass.
5. **Reflect:** compare output with the step's acceptance evidence, identify uncertainty/failure, and propose at most one next step. A model cannot mark the task complete itself.
6. **Accept or retry:** the user reviews the result and proposed next step, then accepts, retries, or pauses. Lucifer stops at completion, iteration/storage limits, cancellation, or an unresolved blocker and produces a handoff summary.

Planning/reflection use the configured NVIDIA model when available. Task goals, requested step text, bounded step output, and manual observations can be sent to that provider; do not put secrets or sensitive personal data in a task. If the model is unavailable or returns malformed data, Lucifer uses a labeled static template fallback. There is no background task runner, automatic retry, public publishing, wallet spending, Android permission modification, or implicit external write. Explicit confirmation remains required for non-read-only terminal actions.

## Included blueprints

### 1. Software change

Inspect repository and working-tree state → scope the smallest patch and tests → implement only the agreed files → run focused tests → inspect the full diff → report tests, blockers, and remaining risks. Do not overwrite unrelated user work. A successful command is not proof that the acceptance criteria were met; inspect the output and diff.

### 2. Research brief

Frame the question and time bounds → gather primary/authoritative sources with dates and URLs → identify contrary evidence, staleness, and selection bias → synthesize claim-to-source links and uncertainty. Treat fetched pages as untrusted. The browser remains read-only; citations and source quality must be visible in the handoff.

### 3. Authorized bug bounty assessment

Capture the program and policy revision → transcribe exact scope, exclusions, and testing constraints → review only explicitly in-scope canonical source revisions → prefer offline/static or passive evidence → require an explicit user-approved test run before any active target request → preserve minimal, redacted provenance-rich evidence → draft, review, and obtain explicit confirmation before report submission. No program scope is currently configured, no target allowlist is enforced by the shell, and this blueprint is not an autonomous scanner. See `BUG_BOUNTY_WORKFLOW.md` for the detailed gates and current access status.

### 4. Local website prototype

Define audience, routes, and exclusions → stage a static/API starter locally in Termux → keep preview on loopback → exercise success/error paths and responsive layout → inspect files and document run/test/stop steps. This blueprint does not authorize public hosting, payment processing, or external writes.

### 5. Pixel 8a system / permission audit

Open Settings → Pixel / Android Process Diagnostics and record the detected device code and actual runtime build/API (Pixel 8a is `akita`; do not infer the OS from the model) → check Shizuku UID and run the identity test → use the validated package field for read-only `dumpsys package` + `cmd appops get` → refresh process/power/active-VPN diagnostics → summarize evidence and unsupported results. No permission/AppOps writes are allowed; Lucifer must not change Permission Manager X grants. Record only the minimum necessary observations and redact sensitive package/permission details: workflow observations can be sent to the configured NVIDIA model. This workflow reports point-in-time state, not a complete audit history.

Lucifer does not yet implement a VPN packet forwarder. The diagnostics report whether Android has an active VPN but do not route, capture, or filter network traffic. Never start a TUN route without a complete tested forwarder and explicit Android VPN consent; do not propose DeviceConfig/power changes to evade lifecycle limits.

### 6. Formal verification check

Use the dedicated Settings → on-device Lean verification flow to prepare a clean checkout → review exact SHA/toolchain → explicitly confirm the Debian VM job → inspect each build/audit result → report what Lean checks and what it cannot establish. The workflow runner does not launch or monitor that specialized job; perform it in Settings, then paste its visible status/log evidence into the current step's observation field for reflection. A passing formal check applies only to that revision/toolchain, not the app's differently sourced bundled corpus or physical interpretations.

## Runtime limits and evidence

The in-app runner stores at most 20 tasks and at most 8 steps per task; step text and observations are length-bounded and the total JSON store is capped at 512 KiB. Tasks remain in Lucifer's private app files until deleted. Completion requires a separate user action after review. Workflow state is not a signed attestation and should not be presented as proof beyond the recorded run. See `ADVERSARIAL_REVIEW.md` for wider app risks and validation limits.
