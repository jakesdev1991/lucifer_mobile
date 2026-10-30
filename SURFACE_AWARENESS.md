# Surface awareness — how Lucifer knows where he is speaking from

Status: **implemented; classifier and answers host-verified, panel behaviour not compiled or
device-tested.** The model-facing prompt block has never been sent to a model from this workspace.

The request: *"If I ask him questions about the interface I'm talking to him on — Termux, VM, chat —
he needs to explain all the details and what capabilities he has relative to what we're communicating
on."*

## The problem this solves

A language model asked *"what can you do here?"* will invent an answer. It has no idea whether "here"
is the launcher, a floating overlay above Termux, or a workflow step, so it produces something
plausible and wrong — and this is the one class of question the user cannot check, because the whole
point of asking is that they don't know.

Lucifer therefore has two mechanisms, and neither is a matter of trust:

1. **A deterministic answer path.** `SurfaceAnswers` recognises a question about the interface and
   replies from compiled data in `SurfaceKnowledge` — no model call, no cost, works offline, and it
   cannot drift from the code that implements the surface.
2. **A surface block in every prompt.** Every other turn still carries
   `SurfaceKnowledge.renderForPrompt(...)`, so a passing "here" or "this terminal" is grounded.

## What he knows about each surface

Ten surfaces are described in `surfaces/Surfaces.kt`. Each has the same fields, and every field is
something he can be asked about:

| Field | What it answers |
| --- | --- |
| `whatItIs` | "Which interface is this?" |
| `transport` | "How does my message get to you, and where does the reply appear?" |
| `canDo` | "What can you actually do from here?" |
| `cannotDo` | "What can't you do here?" |
| `confirmation` | "Will you ask me before running anything?" |
| `leavesDevice` | "What does this send?" |
| `limits` | "Why is that the limit?" |

The surfaces: **launcher chat**, **control-app chat**, **terminal tab** (LOCAL/TERMUX/VM/SHIZUKU),
**floating overlay over Termux**, **floating overlay over the Debian VM**, **workflow runner**,
**recovery panel**, **agents panel**, **heartbeat** (non-interactive), and **UNKNOWN**.

UNKNOWN exists on purpose. It is what a caller that forgets to declare its surface gets, and it
describes itself as the most restricted case rather than assuming it is the launcher:

```
What I can do here:
· Answer questions and describe himself honestly.

What I cannot do here:
· Assume any terminal, panel or overlay capability.
· Run anything, because no surface's confirmation rule can be shown to you here.
```

## Live state, not a brochure

A capability list is only useful if it is current. `SurfaceRuntime` reads, at answer time:

- the network switch (Settings → Privacy) and whether a model key exists,
- whether Termux is installed **and** holds the run-command permission,
- whether the VM host is configured **and** its SSH host key is pinned (unpinned = commands refused),
- whether Shizuku is present, whether overlay permission is granted, whether a command is already
  waiting for confirmation.

Answers then say *"Termux: installed, run-command permission not granted"* instead of claiming a
capability the device cannot deliver today.

## The honesty clause

Every WHERE and CAPABILITIES answer ends with the same sentence in spirit:

> I know this because the app tells me which surface I am on. I cannot see your screen.

"Totally aware" here means **accurately informed**, not secretly watching. He knows which interface he
was invoked from because the app passes it in; he does not see rendered pixels, your typing, or another
app's contents. That distinction is in the compiled text, so no prompt edit can quietly upgrade him to
surveillance.

## Preventing false confidence

The classifier is deliberately conservative, because a **false interception is worse than a miss**: the
user asks about a failing build and gets a lecture about his own overlay. Both implementations ship the
same negative cases as tests:

| Not captured | Why |
| --- | --- |
| "what can you do about this failing build?" | Bare "what can you do" only matches when nothing follows. |
| "can you open chrome for me" | No surface word; goes to the router as a command. |
| "what does this error mean?" | Not a question about the interface. |
| "why can't you connect to the VM?" | That is about a failure, not a limit. |
| "what do you send in the HTTP headers" | Data questions need an anchor ("when…", "to the model…"). |

Anything not matched falls through to normal conversation, which still carries the surface block.

Writing those tests caught four real classifier bugs before they shipped: no tolerance for "what can
you *actually* do", no match for "can you run *a* shell", and two false positives that would have
hijacked ordinary questions.

## Where it is wired in

| Call site | Surface |
| --- | --- |
| `LauncherActivity` chat overlay | `LAUNCHER_CHAT` |
| `MainActivity` control-app chat | `CORE_CHAT` |
| `MainActivity` workflow step | `WORKFLOW` |
| `MainActivity` recovery advice | `RECOVER` |
| `FloatingChatService` overlay | `FLOATING_TERMUX` or `FLOATING_VM`, from the overlay's own target |
| `ToEReasoningEngine` → council | passes the surface through to every persona |

`tools/surface-core/audit_surfaces.py --check` reads the tree and **fails if any reasoning call site
does not pass a `Surface`**, or if a declared surface has no description. So "he is aware everywhere"
is a build-time property, not a code-review habit.

## Knowing how he thinks, not only where he is

Since the reasoning loop landed, "how do you think?" is the same class of question as "what can you do
here?" — and it gets the same treatment. `SurfaceQuestion.REASONING` catches it ("do you use tools?",
"are you just guessing?", "what tools do you have", "how do you check that?", "step by step") and
`CognitionKnowledge.describe()` answers **from the live machinery**: the tool list comes from
`ToolCatalogue.specs()`, the budgets from `LoopBudget`. It cannot name a tool that does not exist or
omit one that does, because it does not contain a written list — it renders the real one. It also carries
the tools' real record on this device (`tools: page_read 4/5 · clock 2/2`) when there is one, so "how do
you think?" is answered with evidence about what has actually worked here, not only with a description of
the machinery.

Every model call also carries a two-line `HOW YOU WORK` block with the same tool ids and the rule that a
device claim requires a device read. A model that does not know it can look will answer from recollection
and sound certain; this is the cheapest possible defence against that.

## What this does not do

- He does not see the UI. He is told the surface identifier and its documented behaviour.
- He does not know whether a command is waiting for confirmation. `SurfaceFacts.pendingConfirmation` is
  tri-state and the runtime reports `null`, so the answer says "I cannot see the confirmation queue from
  this interface" instead of the false "nothing is waiting" it used to assert with no evidence.
- The reasoning answer describes the *scaffold* (tools, budgets, audit), not his internal state — he
  cannot observe his own activations any more than he can see the screen.
- The `TERMINAL_TAB`, `AGENTS` and `HEARTBEAT` descriptions exist for when those surfaces are wired to
  chat, and for answering "what happens if I use that one?" — not because he is currently invoked from
  them.
- None of this has been compiled by Gradle or run on a device. The Kotlin harness
  (`tools/surface-core/SurfaceCoreTests.kt`) has not executed here either — there is no JVM — so the
  Python mirror in `tools/surface-core/spec_check.py` is what currently passes.

## Verification

```bash
./scripts/verify_surface_core.sh                    # spec (72 checks) + Kotlin harness + audit
python3 tools/surface-core/spec_check.py            # recall, precision, answer shapes
python3 tools/surface-core/audit_surfaces.py        # which call sites declare a surface
python3 tools/surface-core/audit_surfaces.py --check # non-zero if one forgets
```

Example exchange it now handles correctly:

```
you  ·  what can you actually do from here?
luc  ·  Here is what I can actually do from the floating chat over Termux.
        What it is: A small draggable chat above Termux, attached to the persistent
        tmux session you started with `tmux new-session -A -s lucifer`.
        What I can do here:
        · Submit a single command line into the shared tmux session after approval.
        · Refresh the pane snapshot, or poll it every four seconds with LIVE.
        · Answer questions about the visible terminal output (up to 2 KB of it is shared).
        What I cannot do here:
        · Run a command outside the tmux session, or start its own shell.
        · See the terminal if the overlay is dismissed or Termux is not running the session.
        · Work without the display-over-other-apps permission.
        Approval: The exact command is shown in the overlay and needs your approval before it enters the PTY.
        Live right now: network switch: ON · model key: configured · selected target: TERMUX · Termux: installed and permitted
```
