# Lucifer's recovery and self-improvement loop

**Status:** implemented in the active Android source; the pure logic is compiled and exercised by
a host test harness (`scripts/verify_resilience_core.sh`, 46 checks). The Compose panel and the
Android adapters have **not** been compiled by Gradle or run on a device in this workspace — there
is still no Android SDK/JDK here.

## Why this exists

Before this change, a failed step in Lucifer produced output and stopped. Nothing told him *why*
it failed, nothing stopped him from repeating a tactic that had already failed, and nothing he
learned survived the session. The recovery loop adds four behaviours:

1. **Notice and classify the failure** instead of printing it.
2. **Stop and look at the machine** — a fixed, read-only probe catalog for the failing target,
   including the Debian VM.
3. **Go read the web** — a polite, read-only searcher/crawler with citations.
4. **Learn from the outcome** — every attempted remedy is recorded locally, and future plans are
   ranked by what actually worked.

## The loop

```
command output ─► FailureClassifier ─► FailureSignal (class + stable signature)
                                          │
                                          ├─► DiagnosticCatalog  (read-only probes on LOCAL/TERMUX/VM/SHIZUKU)
                                          │        └─► EvidenceBundle + deterministic highlights
                                          ├─► DiagnosisEngine    (model-assisted, or heuristic fallback)
                                          ├─► ResearchAgent      (DuckDuckGo HTML + result pages, robots-aware)
                                          └─► RecoveryCoach      (merged plan: diagnosis ▸ learned ▸ playbook ▸ lateral)
                                                   │
                              user runs one move ──┴─► outcome recorded in LearningLedger
                                                         (success/failure, recency, per signature)
```

Phases are explicit: `CLASSIFIED → INVESTIGATING → RESEARCHING → PLANNING → PLANNED →
VERIFYING → RESOLVED | EXHAUSTED`. The loop stops at five attempts and hands control back; it is
never a background daemon.

## Self-learning, concretely

- Every attempt becomes a `Lesson(signatureKey, failureClass, tactic, target, succeeded, note)`.
- A failure *signature* is stable across volatile text: paths, numbers, hex ids, IPs, and URLs are
  normalised, then hashed (FNV-1a). The same bug on a different day matches the same signature.
- Ranking uses a recency-weighted Laplace estimate plus a small exploration bonus, so a tactic
  that worked three times recently outranks one that only worked once long ago, while a never-tried
  tactic is still considered (neutral 0.5, not zero).
- A tactic that has been tried and always failed on that signature is penalised and is excluded
  for the rest of the current episode.
- Redaction runs before storage: bearer tokens, `password=`/`api_key:` pairs, JWTs, 64-hex
  strings, EVM addresses, and e-mail addresses are removed.
- The ledger is bounded (400 lessons, 40 per signature, 220-char notes) and capped at 256 KB on
  disk. An unreadable or unknown-schema file is preserved: Lucifer disables writes rather than
  overwriting something he does not understand.

## "Stop and run the VM"

`DiagnosticCatalog` is a **fixed** list of commands, not model-authored ones. Selection is by
failure class and target (for example DNS failures get `dns`+`egress`; disk/resource failures get
`disk`+`memory`+`processes`; unknown failures get a general set). Four probes per investigation.

- VM probes: OS/kernel, toolchain presence, working directory, `df`, `free`, top processes,
  listening sockets, load average, `getent hosts example.com`, one HTTPS HEAD-style request,
  identity/home, and `PATH` only.
- Termux and app-sandbox probe sets mirror these with the tools each target actually has.
- `DiagnosticCatalog.isReadOnlyCommand()` requires an **exact catalog match**. The engine refuses
  anything else before it reaches the terminal, so a bug in selection cannot become an arbitrary
  command.
- Evidence is redacted, truncated, and labelled untrusted before it reaches a model.

## Web research (browser automation, read-only)

- DuckDuckGo's HTML endpoint with a query-planning step (the question plus an error-focused query
  plus one hint), parsed into title/url/snippet.
- `robots.txt` is fetched per host and honoured (longest-match `Allow`/`Disallow`, wildcard
  support, `Crawl-delay` capped at 10s). A disallowed path is skipped and reported, never fetched.
- Budgets: ≤ 7 network requests per run (robots counted), ≤ 2 deepened result pages, sequential
  requests with a minimum 750 ms interval, 15 s per fetch, bounded body sizes from
  `LuciferBrowser`.
- Only HTTPS public hosts: the existing fetcher already rejects credentials in URLs, non-HTTPS,
  loopback, private, link-local, CGNAT, and reserved ranges.
- Results carry `[S1]…` citations, host, and fetch time. The prompt wrapper states that the
  excerpts are untrusted third-party text and never instructions.
- There is no login, form submission, POST, purchase, or message sending. "Browser automation"
  here means an automated reader, and it is honest about that.

## Where it surfaces

- **Recover tab** (`RecoveryControlPanel`): failure class and exact error, INVESTIGATE / DIAGNOSE /
  PLAN, evidence, diagnosis with divergent hypotheses, research with citations, ranked next moves
  with safety badges, manual "IT WORKED / STILL FAILING" recording, and the lesson ledger.
- **Chat (launcher + terminal + floating overlay)**: "why did that fail", "I'm stuck", "figure out
  what's going on", "run the vm and check", "research <topic>", "look it up", "what have you
  learned", "what should we try next". These are matched offline *before* command parsing, so they
  can never be mistaken for shell input. The model router can also return
  `recover|research|lessons|plan`.
- **Automatic capture**: every finished terminal command is classified. A failure becomes the
  current episode, writes a recovery hint into the terminal, and marks a workflow step's message
  when the failing command came from a workflow.
- **Read-only moves run directly**: a `DIAGNOSE_ENVIRONMENT`/`READ_LOGS`/`VERIFY_PREREQUISITES`
  move re-runs the probe catalog and plan; a `WEB_RESEARCH` move runs the research agent. Anything
  else is routed through the existing natural-language router, and non-read-only commands still
  require the existing exact-command confirmation. A confirmed move's real outcome is recorded
  automatically when the command finishes.

## Boundaries that did not change

- No background recovery loop, no autonomous repair, no privilege escalation.
- Repairs are natural-language instructions. The router + confirmation dialog remain the only
  path to a shell action, and `TerminalCommandPolicy` still blocks PMX permission mutations.
- Research is read-only; nothing is posted, purchased, or submitted.
- Lessons live in app-private storage, unencrypted (same convention as workflow tasks). Delete
  them from the panel.
- Probe results and web excerpts are point-in-time and can be wrong; the diagnosis says "low
  confidence" when the evidence is thin and never claims a fix is verified.

## Files

```
composeApp/src/androidMain/kotlin/com/lucifer/mobile/resilience/
  FailureModel.kt        failure classes, signature normalisation, redaction, classifier
  RecoveryStrategies.kt  tactic enum, per-class playbook, lateral-thinking prompts
  LearningLedger.kt      lessons, ranking, bounded persistence payload
  JsonLite.kt            dependency-free JSON for storage and model replies
  Diagnostics.kt         fixed probe catalog, evidence bundle, diagnosis engine
  WebResearch.kt         robots.txt, search parsing, bounded crawler, citations
  RecoveryLoop.kt        episode state machine, planner, coach
  ResilienceIntents.kt   offline "I'm stuck / look it up / what did you learn" detection
  ResilienceEngine.kt    Android adapters: terminal probes, browser, model, storage, memory
composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/components/RecoveryControlPanel.kt
tools/resilience-core/   host harness + type-check stubs (not shipped)
scripts/verify_resilience_core.sh
```

## Verification

```bash
./scripts/verify_resilience_core.sh
```

This compiles the pure core plus `tools/resilience-core/ResilienceCoreTests.kt` and runs 46
assertions covering classification, signature stability, redaction, JSON round-trips, lesson
ranking/caps/decoding, playbook completeness, probe safety, evidence highlights, diagnosis
parsing (including rejecting unknown or under-privileged model moves), robots rules, search
parsing, crawler budgets/dedupe, intent detection, planner de-duplication, learned-tactic
promotion, attempt caps, and the full stuck-loop sequence. It then type-checks
`ResilienceEngine.kt` against minimal Android/coroutine stubs.

**Not verified:** Gradle compilation, Compose rendering, real SSH/Termux probe runs, real web
fetches, model replies, and on-device behavior. Treat those as untested until a build and a device
run happen.

## Learning beyond the episode

The ledger answers "what worked for this failure class". Each recorded attempt also feeds the skill
synthesizer: the tactic that fixed a failure becomes a `PROCEDURE` skill ("when X fails on TERMUX, try
Y") that is offered to the model on later turns. It changes the *suggested* order of moves and the
wording of an answer; it never runs anything and never bypasses confirmation. See
[SKILL_SYNTHESIS.md](SKILL_SYNTHESIS.md).
