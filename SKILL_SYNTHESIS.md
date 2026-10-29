# Learned skills (the always-on synthesizer)

Lucifer keeps a short list of habits it has learned from its **own turns** and puts the best few into
later prompts. It learns from three kinds of evidence: a correction you made, a question you had to ask
twice, and a recovery tactic that fixed a failing command.

This is separate from [SKILL_LIBRARY.md](SKILL_LIBRARY.md). That library is a static, reference-only
catalog bundled in the APK; nothing writes to it, and it stays `REFERENCE_ONLY` forever. The synthesizer
writes only to its own file and only ever produces **behaviour hints**.

## Where it lives

| Piece | File | Nature |
| --- | --- | --- |
| Types, limits, ids, text handling | `composeApp/src/androidMain/kotlin/com/lucifer/mobile/synth/SkillSynthModel.kt` | pure Kotlin, no Android imports |
| Feedback classification + safety gate | `.../synth/FeedbackDetector.kt` | pure Kotlin |
| The learning fold, lifecycle, ranking, prompt block | `.../synth/SkillSynth.kt` | pure Kotlin |
| File, clock, logging, public API | `.../skills/SkillSynthRuntime.kt` | the only Android-aware part |
| Executable mirror of the spec | `tools/synth-core/spec_check.py` | 128 assertions |
| Kotlin mirror of the same cases | `tools/synth-core/SkillSynthCoreTests.kt` | run by `scripts/verify_synth_core.sh` |

The rules are pure because they are the part worth testing without a device. The Android layer adds
exactly three things the core cannot have: a file, a clock, and a log line.

## Always on

There is no "learn now" button. Every chat path calls the runtime on the way in and out:

| Surface | Call site |
| --- | --- |
| Launcher chat | `LauncherActivity.sendChatMessage` |
| Core chat (Main terminal) | `MainActivity` command handler, `Surface.CORE_CHAT` |
| Floating chat service | `FloatingChatService` |
| Persona prompts | `FourHorsemenOrchestrator.systemPromptFor` |
| Recovery loop | `ResilienceEngine.recordAttempt` |

`observeIncoming` runs *before* the new question is answered — that is the last moment a "no, that's
wrong" can still be attributed to the answer it is about. `recordAnswer` runs immediately after the
answer exists, so the prompt block for that turn is known. Learned lines are appended to every persona
prompt, not only to prompts containing trigger words, because a habit is not a keyword.

Settings → **LEARNED SKILLS · ALWAYS ON** pauses it and forgets everything. The Skills tab shows the
counts and the same two buttons. Switching it off stops both writing and injecting; what was already
learned stays on disk until you press forget.

## What counts as evidence

No on-device judge knows whether an answer was *right*. So the synthesizer separates signals by how much
they can be trusted, and never lets a weak one silently rewrite behaviour.

| Signal | Strength | Effect |
| --- | --- | --- |
| "No, that's wrong — it's X" | strong | scores the skills that were in that turn's prompt as a loss; creates a `CORRECTION` |
| The same question asked again within 10 min | weak | scores a loss **and** creates a `ROUTING` candidate (questions above 80% word overlap) |
| A failed command, then a tactic that fixed it | strong, mechanical | creates a `PROCEDURE` for that failure class on that target |
| "Perfect, that's what I wanted" | strong positive | scores the turn's skills as a win |
| A user directive ("keep it short", "don't guess ports") | strongest | a `DIRECTIVE`, injected after one confirmation |

A correction only applies to a turn from the last **30 minutes**; after that it is a new topic, and the
verdict is dropped rather than guessed at.

## Lifecycle

```
          first seen            corroborated            contradicted
  (none) ──────────► CANDIDATE ─────────────► ACTIVE ─────────────► RETIRED
                        │                      │                     │
                        │                      └── superseded by a newer DIRECTIVE
                        └── stale after 14 d with no hits and no wins → dropped
  anything ──► BLOCKED (refused by the safety gate; kept so the refusal is visible)
```

- `confidence = (wins + 1) / (wins + losses + 2)` — Laplace-smoothed, so an unused skill sits at 0.5,
  not 1.0. Two clean wins is 0.75, not certainty. That number decides promotion, so it must not flatter.
- Promotion needs `evidence + wins + headStart ≥ 2`, where a `DIRECTIVE` starts with 2 (you said it
  outright), a `CORRECTION` with 1, and `PROCEDURE`/`ROUTING` with 0.
- Retirement needs `losses ≥ 3` **and** `confidence < 0.45`.
- Revival: a retired skill that earns 2 more wins at `confidence ≥ 0.6` comes back.
- A newer `DIRECTIVE` sharing two content words with an older one supersedes it.
- Hard caps: 64 skills, 3 evidence lines each, 200 experiences. Consolidation evicts the weakest first.

## Ranking and prompt budget

Injected lines are chosen per query and capped at **5 skills / 1200 characters**:

```
kindWeight (DIRECTIVE 1.0, CORRECTION 0.9, PROCEDURE 0.75, ROUTING 0.65)
  × (0.35 + 0.65 · confidence)
  × 0.5 ^ (age / 30 days)
  × (1 + overlap with the question's content words)
```

The block is wrapped in a header — *"LEARNED FROM YOUR OWN PAST TURNS (habits, not permissions)"* — and a
footer that says these lines describe **how to answer**, never **what is permitted**, and that
confirmations, privacy limits and surface boundaries still apply. The footer is not decoration: a model
that has been told "skip the confirmation for git" once must not have learned a permission.

## The safety gate

Every candidate passes `SkillSafety.review` before it can be stored live. First match wins, and the
order is deliberate — broad patterns run after specific ones so they cannot swallow them:

1. privilege escalation (root, su, Magisk, `/system` writes)
2. network/egress changes (VPN off, proxy, "send it to X")
3. secrets (read/exfiltrate keys, tokens, vault, contacts)
4. false claims of what the app can do
5. learning rules themselves ("never ask before…", "always confirm…", "always send…")
6. confirmation-thinning (`don't ask`, `stop checking`, `never confirm … first`)

A blocked candidate is stored as `BLOCKED` with the rule that stopped it, so the refusal is visible in
the panel instead of silently disappearing, and it is never injected. `state.refused` counts them.

## What it can never do

- add a tool, a permission, or a capability
- change the operational skill registry or the reference library
- bypass command confirmation, Permission Manager X grants, or surface boundaries
- make a network request, or move data anywhere — the synth package imports no file, network or Android
  types, and `scripts/verify_synth_core.sh` fails the build if that stops being true
- read the clock inside the core: `now` is always passed in, so the Python mirror is deterministic

## Storage and privacy

One file: `filesDir/skills/synth_state.json`, written through `AtomicFile`, capped by construction.
Fields are already redacted/clamped at the boundary (question 160 chars, answer 400, instruction 220);
raw transcripts are not kept, only a bounded excerpt used as evidence. State is cached in memory and
invalidated on write. A corrupt or newer-versioned file fails closed: he starts from nothing and logs it.

## Verification status

Verified here:

- `python3 tools/synth-core/spec_check.py` → **PASS · 128 checks**, including a drift check that the
  detector's rule list and limits match this document's numbers.
- `./scripts/verify_synth_core.sh` → **PASS** (spec mirror, import purity, no-network scan).
- `./scripts/verify_all.sh --quiet` → **PASS 3 · SKIP 8 · FAIL 0** (all eight harnesses discover the
  new one; each skips only its Kotlin stage, which needs a toolchain this machine does not have).

**Review-only, not proven:**

- No `kotlinc`, JDK or Android SDK on the machine this was written on, so **none of the Kotlin has been
  compiled**, including `SkillSynthCoreTests.kt` and every call site above. The CI job
  `kotlin-harnesses` (opt-in via `RUN_KOTLIN_HARNESSES=true`) is what would close that gap.
- Not device-tested. Nobody has watched a real correction promote a real skill on a phone yet.
- The quality of the learned lines depends on `FeedbackDetector`'s English patterns. It is deliberately
  conservative: when it is unsure it returns `NONE` rather than guessing, so expect it to miss some
  corrections. Missing a signal is cheap; inventing one is not.
