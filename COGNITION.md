# The cognition scaffold

**Date:** 2026-10-30
**State:** implemented as pure Kotlin + an Android adapter, verified by 103 executable spec checks and a
9-invariant fuzz harness. **Nothing here has been compiled or run on a device** — this workspace has no
JDK, kotlinc or Android SDK, so every Kotlin claim below is a code review, not a test result. The Python
spec that mirrors it does run, on every push.

---

## 1. The gap this closes

Before this, Lucifer's reasoning was one fixed council: CONQUEST plans, WAR attacks it, FAMINE
synthesises. Three model calls, no tools, no steps, no check on the result.

That structure has a specific failure: **it produces sentences about reasoning instead of doing any.**
Ask it something that requires looking and it will answer from recollection, in a confident voice,
because nothing in the pipeline ever gave it the option to look. And because there was no stage between
"the model wrote a paragraph" and "the user reads it", a wrong sentence about the device — the one class
of claim the user cannot check by reading — went out unchecked.

The scaffold adds five pieces and wires them into the existing engine:

| Piece | File | What it settles |
|---|---|---|
| Working memory | `cognition/CognitionCore.kt` | what is known so far, bounded, compacted by priority |
| Tools | `CognitionCore.kt` + `CognitionRunner.kt` | what he can *do* mid-thought, with the side effect declared |
| The loop | `CognitionRunner.kt` | think → act → observe → think, until a budget says stop |
| Triage | `CognitionCore.kt` | whether a question deserves any of that at all |
| Pre-answer audit | `AnswerAudit.kt` | grounded? overclaimed? does it stand up *before* it is shown |

---

## 2. The loop

```
triage ──shallow──▶ existing single-pass path (cheap, fast, unchanged)
   │
   └─deep─▶ ┌──────────────────────────────────────────────┐
            │ 1. model returns THOUGHT + TOOL, or + ANSWER │
            │ 2. the tool's SideEffect is checked          │
            │ 3. allowed?  → run it, append the result     │
            │    refused?  → stop and say so               │
            │ 4. LoopGuard: steps, wall clock, repetition  │
            │ 5. on ANSWER: audit → (one repair) → done    │
            └──────────────────────────────────────────────┘
```

The loop is bounded on three axes and stops on the first one it hits, reporting **which**:

| Budget | Default | Why that number |
|---|---|---|
| steps | 8 | a useful answer usually needs 1–3 reads; eight is enough to recover from wrong tool choices, not enough to burn a quota |
| wall clock | 90 s | a person waiting on a chat turn will tolerate about a minute and a half before assuming it hung |
| observation chars | 24 000 | past this the observations start evicting the question itself, which is worse than stopping |
| identical repeats | 2 | three identical calls is not exploration, it is a loop |

Stopping is never silent. `CognitionOutcome.stop` carries one of `ANSWERED`, `STEPS_EXHAUSTED`,
`TIME_EXHAUSTED`, `STALLED`, `OBSERVATION_LIMIT`, `TOOL_REFUSED`, `CANCELLED`, and when the loop ends
without an answer it says *"I stopped before I could answer: used all 8 steps without concluding"* and
shows what it did establish. An empty reply would be a claim that there is nothing to say.

---

## 3. Working memory: what gets forgotten, and in what order

A loop that appends everything it sees runs out of context on the fourth step and starts forgetting the
*goal* — the failure that makes agents look stupid. So entries are typed, and compaction is by priority,
not arrival:

| Kind | Priority | Survives |
|---|---|---|
| `GOAL`, `CONSTRAINT` | pinned | always (a constraint like "no root" must outlive everything) |
| `DECISION` | 1 | almost always |
| `QUESTION` | 2 | usually |
| `FACT` | 3 | late |
| `REFLECTION` | 4 | before facts |
| `ACTION` | 5 | before reflections |
| `OBSERVATION` | 6 | first to go, oldest first |

The renderer spends its 6 000-character budget in the same order, so a 6 000-character tool output
cannot push the question out of the window. `render()` is what the model sees each step; `summary()`
is what a status surface shows ("1 goal · 2 constraints · 5 observations").

---

## 4. Tools: declared, not inferred

Five tools ship, and each declares what calling it does to the world:

| Tool | Effect | Confirmation | What it really does |
|---|---|---|---|
| `corpus_search` | `NONE` | no | searches the embedded ToE corpus |
| `memory_search` | `NONE` | no | searches local episodic memory |
| `device_facts` | `DEVICE_READ` | no | reads build, package, network and memory state |
| `clock` | `NONE` | no | the actual time |
| `page_read` | `NETWORK_READ` | no | fetches a public page **through the egress gate**, so it appears in the ledger and rides Tor when Tor is on |

Anything with `LOCAL_WRITE`, `DEVICE_CHANGE` or `EXTERNAL_SEND` requires the tool id to be in
`confirmedTools`, which is populated by a person and **defaults to empty**. When the loop wants such a
tool, it stops:

> it wanted to change the device with `package_disable`, which needs your confirmation first

It does not route around the refusal, and it does not claim the call happened. That single rule is what
makes it safe to let a model choose its own actions at all.

An **invented** tool (`rm_rf("/")`, `exec("…")`) is refused with `no tool named …` and the refusal is fed
back as the next observation, so the model learns the boundary instead of retrying blind.

---

## 5. The plan format, and the one ordering rule that matters

```
THOUGHT: the corpus may define entropy in a way this question contradicts.
TOOL: corpus_search("entropy and the arrow of time")
```
or
```
THOUGHT: two observations agree; nothing else can be checked from here.
ANSWER: …
```

The parser is strict about tool names and lenient about everything else — a model that writes
`ANSWER –` instead of `ANSWER:` still gets its answer through, because failing a turn over punctuation is
worse than accepting it.

**Order decides.** If `ANSWER:` appears *before* a `TOOL:` line, the tool line is part of the answer body —
prose about a tool, or a code block it is describing — and is not executed. The fuzz harness asserts this
over random inputs; the spec asserts it with a hand-written case. Without that rule a model that explains
"you would call `TOOL: corpus_search(...)`" would actually call it.

---

## 6. The pre-answer audit

The audit cannot tell truth from falsehood. It can tell whether the answer was grounded in anything the
loop actually observed, and it can refuse to attach confidence it has not earned.

| Finding | Severity | Trigger |
|---|---|---|
| `EMPTY` | blocker | nothing was produced |
| `THIN` | blocker | < 12 characters in reply to a question longer than 40 |
| `UNGROUNDED` | warning | tools returned material and the answer shares no word with it |
| `OVERCLAIM` | warning | "guaranteed", "I have verified", "proven to work"… with no device read |
| `UNVERIFIED_DEVICE_CLAIM` | warning | "your phone", "is installed", "your storage"… with no device read |
| `CITES_WITHOUT_SOURCE` | warning | `[Vol 12]` with no corpus read this turn |
| `ECHO` | warning | the answer restates the question |
| `HEDGE_STACK` | note | three or more hedges |

Confidence is a four-rung ladder and it is never flattering:

| Rung | Meaning |
|---|---|
| `NONE` | the reply was unusable, not merely uncertain |
| `LOW` | no tool was consulted — this is recollection |
| `MEDIUM` | grounded in one observation, or grounded with caveats |
| `HIGH` | two or more tool results and every check passed |

A blocker buys exactly **one** repair attempt. If the second draft still fails, the loop says so and shows
its working instead of shipping it. Every deep answer ends with one line:

> `Confidence: MEDIUM — 2 tool result(s), including a device read.`

---

## 7. Triage: not every message deserves eight steps

Costs are real: each step is a model call (money and battery), and a looped answer to "hey" reads worse
than a plain one. So:

| Input | Depth | Why |
|---|---|---|
| greeting, "thanks", ≤ 60 chars | shallow | one pass is enough, and cheaper |
| no tools available | shallow | extra steps could only cost time |
| a reasoning shape ("design", "debug", "why does", "how do I") **and** a second clause | deep | this is what the loop is for |
| ≥ 320 characters | deep | one pass will miss part of it |
| everything else | shallow | a plain question |

The classifier errs shallow on purpose: a shallow answer can be followed up, a slow one cannot be taken
back. `triage.reason` is always populated, so a future UI can say *why* a turn was treated as ordinary.

---

## 8. What it does not do

- **No self-modification.** The loop cannot change its own prompts, tools or budgets; those are compiled
  constants. A synthesizer that rewrites its own instructions is how an agent drifts without anyone
  noticing.
- **No multi-tool steps.** One tool per message, by design — parallel calls make the trace unreadable and
  the confirmation model ambiguous.
- **No code execution.** "Run this Python" is not a tool. The terminal exists and stays behind its own
  confirmation; the loop cannot reach it.
- **No memory writes.** `LOCAL_WRITE` tools exist in the model but none ship, so the loop cannot silently
  change what he remembers. Memory is still written by the turn-level path that the user can see.
- **No claim of correctness.** The audit checks grounding, not truth. A grounded answer can still be
  wrong, and the footer says what was checked — not that the answer is right.

---

## 9. Verification

```
python3 tools/cognition-core/spec_check.py         PASS · 103 checks
python3 tools/cognition-core/fuzz.py 6000          FUZZ OK · 30 600 cases · 9 invariants
./scripts/verify_cognition_core.sh                 PASS (Kotlin half SKIPPED — no kotlinc)
./scripts/cognition_sweep.sh --loop 0 --interval 90   the looping run
```

The nine fuzz invariants, which are what makes it safe to let a model write the input:

1. the parser never returns a call to a tool that was not offered
2. the parser never returns an argument longer than that tool's cap
3. an `ANSWER` preceding a `TOOL` line is never executed as a tool
4. working memory never exceeds capacity and never drops the first goal while pinned entries fit
5. the guard always reports a stop reason once a budget is passed, and never invents a termination
6. confidence is never `HIGH` without two observations
7. a blocked audit is never `MEDIUM` or `HIGH`
8. triage never says `DEEP` with no tools
9. nothing raises, for any input — including NUL bytes, RTL overrides and 5 000-character arguments

`sweep.sh` runs spec + fuzz (new seed each pass) + the static claims + `verify_all`, appends one line per
pass to `build/sweep/report.log`, and repeats. For a crontab:

```
*/30 * * * * cd /path/to/lucifer_mobile && ./scripts/cognition_sweep.sh --quiet >> /tmp/lucifer-sweep.log 2>&1
```

**Not proven here:** the loop has never called a model, never run a tool, never shown a trace. The Kotlin
harness (`tools/cognition-core/CognitionCoreTests.kt`) has not compiled in this workspace. Every claim in
this document is about code as written.
