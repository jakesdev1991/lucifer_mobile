# Agents, prompt files, and the General

Lucifer's behaviour is no longer one hard-coded prompt. Three kinds of file are now the source of
truth, and an always-on tactician reads what the app already records to decide what should change
next.

Status: **implemented, host-verified, not compiled with Gradle or run on a device.** The pure core
(parsing, validation, composition, observation ledger, detectors, budget, tactician loop) is covered
by 50 assertions in `./scripts/verify_agents_core.sh`; the Android adapters type-check against
stubs. The Compose panel and the foreground service have **not** been compiled by AGP or executed on
device.

## The three files

| File | Location | Who writes it | What it controls |
| --- | --- | --- | --- |
| `soul.md` | `files/soul.md` (asset `soul.md` is the fallback) | user | Identity and standing rules for chat, heartbeat sessions, and the cognitive router |
| `heartbeat.md` | `files/heartbeat.md` | user | The directive every heartbeat session follows |
| `agent-class.md` | `files/agents/<id>/agent-class.md` | user or Lucifer | One agent's role, mandate, skills, and triggers |

All three are edited in the **Agents** tab (and `soul.md` in Settings). Writes are atomic, validated,
and rejected edits change nothing.

Why this matters: before this change the heartbeat prompt was a `"""` string in
`HeartbeatSessionEngine`, and `MetaCognitiveRouter` read the APK asset directly while `NemotronClient`
read the override — two different identities depending on the code path. Now the heartbeat composes
its session prompt from `heartbeat.md` + `soul.md` + the General's standing guidance, and every soul
read goes through `PromptStore`.

### Agent file format

```markdown
---
id: disk-medic
name: Disk Medic
role: operator          # general | scout | researcher | builder | analyst | operator | custom
created_by: lucifer     # user | lucifer | system
enabled: true
version: 3
skills: recovery, cleanup
targets: LOCAL,TERMUX,VM,SHIZUKU
triggers: disk, space, storage
---
# Disk Medic

## Mandate
...
```

Rules enforced by `AgentClassFile` and `PromptRules`:

- `id` must match `^[a-z][a-z0-9_-]{1,39}$` and match its folder name. Missing front matter is
  tolerated (the filename becomes the id and the whole body becomes the mandate), so a plain
  Markdown file still works.
- A mandate shorter than 12 characters, longer than 16,000, or containing an obvious credential
  (`sk-…`, `nvapi-…`, `AKIA…`, `password: …`, private-key blocks) is rejected. Prompt files are
  instructions, not a secret store — they are sent to the model on every call.
- Lists are clamped (12 items, 40 characters each); unknown `targets` are dropped; the roster caps at
  24 agents.
- `skills` and `triggers` are **routing hints only**. They never grant a tool, a target, or a
  permission. Every agent inherits the same boundaries and the same confirmation flow.

### Creating agents

- **User-written:** type a name and a goal in the panel → a template draft with the boundary block →
  you edit it as a file.
- **Lucifer-written:** the same request goes through the model with a bounded JSON contract; the
  deterministic draft is the fallback, and the model's mandate is rejected if it is short, credential
  bearing, or missing. The id, author, and boundary block come from the deterministic path, not the
  model.
- **From the General:** approving a `SKILL` or `AGENT` proposal composes an agent from that proposal's
  evidence and saves it as a file with `created_by: lucifer`.

## The General (tactician)

`agents/android/TacticianService.kt` is a user-started foreground service, notification visible with a
Stop control, opt-in, resumable after boot only if it was enabled. It runs one bounded pass roughly
every 60 minutes.

### Senses

Only what Lucifer already records, and only summaries:

| Sense | Source | Not read |
| --- | --- | --- |
| App installs / removals / updates | `launcher/PackageEventReceiver` + `files/memory/app_events.log` | which app content |
| Commands and outcomes | terminal hook in `MainActivity` | full output beyond the last 6 lines, redacted |
| Recovery episodes and lessons | `files/resilience/lessons.json` | — |
| Workflows | `files/workflows/tasks.json` | step bodies beyond status counts |
| Heartbeat sessions | `files/heartbeat/sessions.jsonl` | draft bodies (only title/summary/importance) |
| Camera **use** | heartbeat record `cameraIncluded` | never the image |
| Model calls | the tactician's own call counter | prompt text |
| Agent runs and prompt edits | `ObservationBus` | — |
| Device state | battery percentage, charging, SDK, once per 6 h | location, identifiers |

Explicitly out of scope: message bodies, browsing history, other apps' private data, keystrokes,
contacts, location.

### Loop

1. `SignalCollector` tails the append-only logs from stored byte offsets and snapshots device state.
2. `OpportunityDetector` runs deterministic detectors — exhausted recovery signatures, missing
   recovery instrumentation, repeated failing commands, permission-denial patterns, unused or
   overlapping agents, invalid agent files, heartbeat drift, camera consent with no camera use,
   stalled or over-parallel workflows, budget pressure, proposal backlog.
3. `TacticianEngine` optionally asks the model for interpretation, with the observation digest as
   **untrusted data**. Detector findings always survive; model proposals are capped at 3.
4. `ProposalLedger` merges by stable fingerprint. A dismissed proposal is never resurrected; an
   approved one becomes `APPLIED` when it produced an agent file.

Every proposal is `APPROVAL_REQUIRED`. There is no path from a proposal to execution.

### Budget

`AutonomyBudget` — 40 model calls/day, 20 minutes minimum between passes, quiet hours 23:00–07:00.
Quiet hours, the interval, and the cap are checked **before** the model call, and a skipped pass
reports why. Observation-ledger growth is bounded (800 entries, 200 per kind, 1.5 MB on disk,
redacted at construction).

## What this is not

- No autonomous execution, no permission changes, no spending, no publishing, no third-party contact.
  The General proposes; you approve.
- No repair loop. Recovery still requires a user-started move, and non-read-only commands still go
  through the existing exact-command confirmation.
- The General does not edit prompts by itself. Prompt edits are yours or an explicit approval.
- No MCP server is created, installed, or restarted at runtime. When memory retrieval keeps missing,
  the General proposes a read-only tool for the **existing** Drive-memory server; approving it writes
  a reviewable spec plus a Termux checklist to `files/tactician/mcp-drafts/` and sets the proposal to
  `APPLIED`. The user adds the tool. See `mcp/DRIVE_MEMORY_MCP.md`.
- Sensing is bounded by what the app already stores. Adding a new sense means adding a new write at
  the source, not widening a permission.
- Observations and proposals are unencrypted in app-private storage. Clearing them does not delete
  lessons or workflow history.
- The service is subject to Android's background limits: passes can be delayed or skipped, and the
  notification must stay permitted.

## Verification

```bash
./scripts/verify_agents_core.sh   # 50 assertions + adapter type-check; needs java 17+ and kotlinc
./scripts/verify_resilience_core.sh
./scripts/verify_design_core.sh   # 16 assertions on the Morningstar chamfer/sigil/ember math
./scripts/verify_privilege_core.sh # 22 assertions on backend routing, refusals, and the world
./scripts/verify_verification_core.sh # 23 control-plane + 16 runtime/transport assertions, plus adapter type-check
```

The harness (`tools/agents-core/AgentCoreTests.kt`) covers malformed agent files, credential
rejection, roster caps, routing and explicit selection, composer guardrails, model-output clamping,
observation bounds and redaction, detector firing, proposal de-duplication and status transitions,
quiet hours, MCP draft validity, and tactician behaviour with no model, a garbage model reply, and an
unknown-category reply.
