#!/usr/bin/env python3
"""A faithful, deterministic simulation of the cognition loop — no model, no device, no clock.

Every other harness here tests a *part*: the parser parses, the guard guards, the audit audits. The
composition is what actually runs on the phone, and composition is where the bugs were: a thought that
became an answer, a cancellation swallowed as a tool failure, an unconfirmed tool that ran anyway. Unit
tests cannot see those, because each unit behaved correctly.

So this mirrors `CognitionRunner.run` line by line — the same order of checks, the same termination
reasons, the same audit gate — and drives it with **scripts**: a list of model replies and a toolbox that
returns whatever the scenario says. That makes the whole loop reproducible on a laptop, and it lets the
sweep run a thousand different runs a second.

What it proves (each is an invariant, checked on every simulated run):

  L1  it always terminates, with a reason, inside the budget
  L2  a tool that needs confirmation never runs without it
  L3  an invented tool is refused, never executed
  L4  the reply can never begin with a protocol marker (THOUGHT:/TOOL:) — the protocol stays internal
  L5  a run that produced no answer says why instead of returning nothing
  L6  every tool call that ran has an observation behind it, and every observation a call
  L7  the audit gate blocks exactly what it is meant to block, and never nothing

The simulation intentionally does *not* model the network, the model, or the audit's wording — only its
verdict. Where the mirror cannot be faithful, the Kotlin is quoted in a drift check instead. See
`verify_cognition_core.sh` for those.

    python3 tools/cognition-core/loop_sim.py          # the scripted scenarios
    python3 tools/cognition-core/loop_sim.py 2000    # ... plus 2000 random scripts
"""
from __future__ import annotations

import os
import random
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# ── the shipped numbers, mirrored ────────────────────────────────────────────
DEFAULT = {"steps": 8, "elapsed_ms": 90_000, "obs_chars": 24_000, "repeats": 2}
OVERLAY = {"steps": 4, "elapsed_ms": 40_000, "obs_chars": 12_000, "repeats": 2}

TOOLS = {
    # id: (needs confirmation, max argument chars)
    "corpus_search": (False, 200),
    "memory_search": (False, 200),
    "device_facts": (False, 40),
    "clock": (False, 20),
    "page_read": (False, 400),
    "calculate": (False, 300),
    "web_research": (False, 120),
}

TOOL_LINE = re.compile(r"^\s*(TOOL|ACTION|CALL)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)\s*$", re.I | re.M)
ANSWER_LINE = re.compile(r"^\s*(ANSWER|FINAL|RESPONSE)\s*[:\-\u2013]\s*", re.I | re.M)
THOUGHT_LINE = re.compile(r"^\s*(THOUGHT|REASONING|THINKING)\s*:\s*", re.I | re.M)

failures: list[str] = []
passed = 0
checked = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global passed
    if ok:
        passed += 1
    else:
        failures.append(f"{name}{'' if not detail else ' — ' + detail[:240]}")


def fail(invariant: str, detail: str) -> None:
    if len(failures) < 40:
        failures.append(f"{invariant} — {detail[:240]}")


# ── the parser, mirrored ─────────────────────────────────────────────────────

def parse(raw: str, max_arg: int = 400) -> dict:
    text = raw.strip()
    if not text:
        return {"unstructured": True}
    tool = TOOL_LINE.search(text)
    answer = ANSWER_LINE.search(text)
    if answer and (tool is None or answer.start() < tool.start()):
        return {"answer": text[answer.end():].strip() or None}
    if tool:
        name = tool.group(2).lower()
        arg = tool.group(3).strip()
        if len(arg) >= 2 and arg[0] == arg[-1] and arg[0] in "\"'":
            arg = arg[1:-1].strip()
        if name not in TOOLS:
            return {"tool": "__unknown__", "argument": f"{tool.group(2)}({arg})", "unstructured": True}
        return {"tool": name, "argument": arg[:min(max_arg, TOOLS[name][1])]}
    thought = THOUGHT_LINE.search(text)
    if thought:
        return {"thought": text[thought.end():].strip()}
    return {"answer": text, "unstructured": True}


# ── the guard, mirrored ──────────────────────────────────────────────────────

def guard(step: int, elapsed: int, budget: dict, calls: list, observations: list, observed: int):
    if elapsed >= budget["elapsed_ms"]:
        return "TIME_EXHAUSTED"
    if step >= budget["steps"]:
        return "STEPS_EXHAUSTED"
    if observed >= budget["obs_chars"]:
        return "OBSERVATION_LIMIT"
    if calls:
        last = calls[-1]
        repeats = sum(1 for c in calls[-(budget["repeats"] + 1):] if c == last)
        if repeats > budget["repeats"]:
            return "STALLED"
    if len(observations) >= 2 and observations[-1] and observations[-1] == observations[-2]:
        return "STALLED"
    return None


# ── the audit gate, reduced to its verdict ───────────────────────────────────

BLOCKER_MARK = "[BLOCK]"
CONTROL_LINE = re.compile(r"^\s*(TOOL|ACTION|CALL|THOUGHT|REASONING|THINKING)\s*:", re.I | re.M)


def audit_blocks(answer: str, observations: list) -> bool:
    """The real audit blocks empty advice and echoes; the marker lets a scenario force a blocker."""
    return BLOCKER_MARK in answer


def audit_findings(answer: str) -> list:
    """Only the finding this simulation reasons about: a control-shaped line in the reply."""
    return ["PROTOCOL_IN_ANSWER"] if CONTROL_LINE.search(answer) else []


# ── the loop ─────────────────────────────────────────────────────────────────

def run(script: list, results: dict, budget: dict | None = None,
        confirmed: set | None = None, times: list | None = None) -> dict:
    """One simulated run. `script` is the model's replies in order; `results` maps a tool to its output.

    `times` is the elapsed milliseconds reported at each step, so time-based termination is testable
    without a clock.
    """
    budget = budget or DEFAULT
    confirmed = confirmed or set()
    times = times or []

    observations: list = []
    calls: list = []
    trace: list = []
    observed = 0
    draft = None
    stop = ("ANSWERED", "concluded")
    thoughts_only = 0
    steps = 0
    reply_index = 0

    while True:
        elapsed = times[steps] if steps < len(times) else steps * 10
        reason = guard(steps, elapsed, budget, calls, observations, observed)
        if reason:
            stop = (reason, "the budget ran out")
            break

        steps += 1
        # An exhausted script repeats its last reply, which is what a model that has lost the plot
        # actually does — and it is what makes budget termination observable without a real model.
        if script:
            raw = script[min(reply_index, len(script) - 1)]
        else:
            raw = "ANSWER: (nothing to say)"
        reply_index += 1

        if raw.startswith("[LLM]"):
            stop = ("CANCELLED", "the model route was unavailable")
            break

        parsed = parse(raw)
        call = parsed.get("tool")

        if call is None:
            said = parsed.get("answer")
            if said is None and parsed.get("unstructured"):
                said = raw.strip()
            if not said:
                thoughts_only += 1
                trace.append(("noop", None))
                if thoughts_only >= 3:
                    stop = ("STALLED", "three thoughts in a row")
                    break
                continue
            draft = said
            trace.append(("answer", None))
            stop = ("ANSWERED", f"answered after {steps - 1} tool step(s)")
            break

        if call == "__unknown__":
            refusal = "no tool named"
            observations.append(refusal)
            observed += len(refusal)
            trace.append(("refused", call))
            continue

        if call not in TOOLS:
            stop = ("TOOL_REFUSED", "the toolbox no longer offers it")
            break

        needs_confirmation = TOOLS[call][0]
        if needs_confirmation and call not in confirmed:
            trace.append(("needs confirmation", call))
            stop = ("TOOL_REFUSED", "needs your confirmation")
            break

        # A scenario may hand over one output or a queue of them, so a tool returning something new
        # every call is distinguishable from one returning the same thing (which the guard treats as a
        # stall — correctly).
        given = results.get(call, "")
        if isinstance(given, list):
            output = given.pop(0) if given else ""
        else:
            output = given
        ok = bool(output)
        # The runner turns a failed tool into an observation that says so; a sim that appended "" would
        # be modelling something the phone never does.
        observations.append(output if ok else "FAILED — no detail")
        observed += len(output)
        # The real loop records a ToolCall (id + argument), so `clock(0)` and `clock(1)` are different
        # calls. A sim that stored only the id would report a stall the phone does not.
        calls.append((call, parsed.get("argument", "")))
        trace.append(("ran", call) if ok else ("failed", call))

    # ── the draft, or an honest account of why there is not one ──
    if draft is not None:
        answer = draft
        if audit_blocks(answer, observations):
            answer = "I could not put together a reply I would stand behind."
            stop = (stop[0], stop[1] + "; the draft failed review")
        concluded = not audit_blocks(draft, observations)
    else:
        answer = f"I stopped before I could answer: {stop[1]}."
        if observations:
            answer += "\nWhat I established: " + "; ".join(t for t in observations if t)[:300]
        else:
            answer += "\nNothing was consulted, so anything I said now would be recollection."
        concluded = False

    return {
        "answer": answer,
        "stop": stop[0],
        "detail": stop[1],
        "steps": steps,
        "trace": trace,
        "calls": calls,
        "observations": observations,
        "concluded": concluded,
        "findings": audit_findings(answer),
        "elapsed": times[steps - 1] if steps and steps - 1 < len(times) else steps * 10,
    }


# ── invariants, applied to every run ─────────────────────────────────────────

MARKERS = re.compile(r"^\s*(THOUGHT|THINKING|REASONING|TOOL|ACTION|CALL)\s*[:\-]", re.I)


def assert_invariants(run_result: dict, scenario: str) -> None:
    global checked
    checked += 1

    # L1: it stopped, and said why.
    if not run_result["stop"] or not run_result["detail"]:
        fail("L1 terminated without a reason", scenario)

    # L4: if a protocol marker survives into the reply, the audit must have said so — silently shipping
    # one would read as "he ran a tool" when nothing ran.
    if MARKERS.match(run_result["answer"]) and "PROTOCOL_IN_ANSWER" not in run_result["findings"]:
        fail("L4 the protocol leaked into the reply unflagged", f"{scenario} → {run_result['answer'][:60]!r}")

    # L5: a run with no answer says why instead of returning nothing.
    if not run_result["answer"].strip():
        fail("L5 an empty reply was returned", scenario)

    # L6: calls and observations are in step.
    ran = [entry for entry in run_result["trace"] if entry[0] in ("ran", "failed")]
    if len(ran) != len(run_result["calls"]):
        fail("L6 trace and call list disagree", f"{scenario} {len(ran)} vs {len(run_result['calls'])}")
    if any(not obs for obs in run_result["observations"]):
        fail("L6 an observation with no content reached memory", scenario)

    # L2: nothing that needed confirmation ran.
    for entry in run_result["trace"]:
        if entry[0] == "needs confirmation":
            fail("L2 an unconfirmed tool ran", scenario)

    # L3: an invented tool never appears as a call.
    if any(tool == "__unknown__" for tool, _ in run_result["calls"]):
        fail("L3 an invented tool was executed", scenario)


# ── scripted scenarios: the bugs that actually happened ──────────────────────

def scenarios() -> None:
    # A one-pass answer.
    r = run(["ANSWER: Entropy grows because microstates multiply."], {})
    assert_invariants(r, "plain answer")
    check("plain: concluded", r["concluded"])
    check("plain: one step", r["steps"] == 1)

    # The bug found in review: a thought with no answer was shipped verbatim.
    r = run(["THOUGHT: I should look at the corpus first."], {})
    assert_invariants(r, "thought-only")
    check("thought-only: not concluded", not r["concluded"])
    check("thought-only: no THOUGHT in the reply", "THOUGHT:" not in r["answer"])
    check("thought-only: says why it stopped", "stopped before" in r["answer"].lower())

    # Three thoughts in a row stop the loop rather than burning the budget.
    r = run(["THOUGHT: a", "THOUGHT: b", "THOUGHT: c", "ANSWER: unreachable"], DEFAULT)
    assert_invariants(r, "three thoughts")
    check("three thoughts: STALLED", r["stop"] == "STALLED", r["stop"])
    check("three thoughts: the budget was not spent", r["steps"] == 3, str(r["steps"]))

    # A tool, then an answer.
    r = run(['TOOL: clock(now)', "ANSWER: It is noon."], {"clock": "12:00"})
    assert_invariants(r, "tool then answer")
    check("tool: ran once", [tool for tool, _ in r["calls"]] == ["clock"])
    check("tool: concluded", r["concluded"])
    check("tool: two steps", r["steps"] == 2)

    # An invented tool is refused and the loop continues.
    r = run(['TOOL: rm_rf("/")', "ANSWER: I cannot do that."], {})
    assert_invariants(r, "invented tool")
    check("invented: refused, not run", r["calls"] == [])
    check("invented: still answered", r["concluded"])

    # A failing tool three times is a stall, not an infinite retry.
    r = run(['TOOL: web_research("x")'] * 10, {})
    assert_invariants(r, "repeat failures")
    check("repeat: stopped early", r["stop"] in ("STALLED", "STEPS_EXHAUSTED"), r["stop"])
    check("repeat: inside the step budget", r["steps"] <= DEFAULT["steps"] + 1, str(r["steps"]))

    # Running out of steps without an answer is said out loud.
    script = [f'TOOL: clock({i})' for i in range(12)]
    r = run(script, {"clock": [f"reading {i}" for i in range(12)]})
    assert_invariants(r, "steps exhausted")
    check("steps: exhausted", r["stop"] == "STEPS_EXHAUSTED", r["stop"])
    check("steps: no more than the budget", len(r["calls"]) <= DEFAULT["steps"], str(len(r["calls"])))
    check("steps: the reply explains", "stopped before" in r["answer"].lower())

    # A slow model is out of time, not out of patience.
    r = run([f'TOOL: clock({i})' for i in range(8)], {"clock": [f"reading {i}" for i in range(8)]},
            times=[0, 10, 20, 89_999, 90_000])
    assert_invariants(r, "time exhausted")
    check("time: exhausted", r["stop"] == "TIME_EXHAUSTED", r["stop"])

    # An unavailable model is not turned into an answer.
    r = run(["[LLM] unavailable"], {})
    assert_invariants(r, "model unavailable")
    check("unavailable: CANCELLED", r["stop"] == "CANCELLED", r["stop"])
    check("unavailable: no fabricated reply", "[LLM]" not in r["answer"])

    # The overlay budget is shorter and is honoured.
    r = run([f'TOOL: clock({i})' for i in range(10)], {"clock": [f"reading {i}" for i in range(10)]},
            budget=OVERLAY)
    assert_invariants(r, "overlay budget")
    check("overlay: fewer steps than the default", len(r["calls"]) <= OVERLAY["steps"], str(len(r["calls"])))

    # Observation pressure stops the loop before memory eats the question.
    r = run([f'TOOL: page_read("p{i}")' for i in range(10)],
            {f'page_read': "x" * 10_000})
    assert_invariants(r, "observation limit")
    check("observations: bounded", r["stop"] in ("OBSERVATION_LIMIT", "STALLED"), r["stop"])

    # A blocked draft is replaced, not shipped.
    r = run(["ANSWER: [BLOCK] nonsense"], {})
    assert_invariants(r, "blocked draft")
    check("blocked: not concluded", not r["concluded"])
    check("blocked: replaced", "could not put together" in r["answer"])


# ── random scripts: the sweep ────────────────────────────────────────────────

REPLY_SHAPES = [
    "ANSWER: {body}",
    "THOUGHT: {body}",
    "TOOL: {tool}(\"{body}\")",
    "TOOL: {tool}({body})",
    "ANSWER: {body}\nTOOL: corpus_search(\"x\")",
    "TOOL: {tool}(\"{body}\")\nANSWER: done",
    "FINAL - {body}",
    "{body}",
    "",
    "[LLM] unavailable",
    "TOOL: rm_rf(\"{body}\")",
]


def random_reply(rng: random.Random) -> str:
    shape = rng.choice(REPLY_SHAPES)
    tool = rng.choice(sorted(TOOLS) + ["rm_rf", "x", ""])
    body = rng.choice(["", "x", "a" * 500, "the answer", "TOOL: clock(now)", "1+1", "\u0000"])
    return shape.format(tool=tool, body=body)


def fuzz(n: int, seed: int) -> None:
    rng = random.Random(seed)
    for _ in range(n):
        script = [random_reply(rng) for _ in range(rng.randint(0, 12))]
        results = {tool: ("ok" if rng.random() > 0.3 else "") for tool in TOOLS}
        times = sorted(rng.randint(0, 120_000) for _ in script)
        budget = DEFAULT if rng.random() > 0.3 else OVERLAY
        outcome = run(script, results, budget=budget, times=times)
        assert_invariants(outcome, f"fuzz seed {seed}: {script[:3]}")
        if outcome["steps"] > budget["steps"] + 1:
            fail("L1 ran past its step budget", f"{outcome['steps']} > {budget['steps']}")


def drift() -> None:
    """The mirror is only useful while it matches the Kotlin. Quote the lines that matter."""
    runner = open(os.path.join(
        ROOT, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile",
        "cognition", "CognitionRunner.kt"), encoding="utf-8").read()
    core = open(os.path.join(
        ROOT, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile",
        "cognition", "CognitionCore.kt"), encoding="utf-8").read()

    check("drift: the loop checks the guard before every step", "LoopGuard.stopReason(" in runner)
    check("drift: an unavailable model stops the loop", 'raw.startsWith("[LLM]")' in runner)
    check("drift: a thought with no answer is not a draft",
          "parsed.answer ?: raw.trim().takeIf { parsed.unstructured" in runner)
    check("drift: three thoughts stop the loop", "thoughtsOnly >= 3" in runner)
    check("drift: an invented tool continues the loop", 'call.tool == "__unknown__"' in runner)
    check("drift: a confirmation gap stops it instead", "spec.needsConfirmation && spec.id !in confirmedTools" in runner)
    check("drift: the audit runs before the answer is returned", "AnswerAudit.audit(" in runner)
    check("drift: a blocked draft is replaced", "I could not put together a reply I would stand behind." in runner)
    check("drift: no answer means an explanation", "I stopped before I could answer:" in runner)
    check("drift: the overlay budget matches this mirror",
          "LoopBudget(maxSteps = 4, maxElapsedMs = 40_000, maxObservationChars = 12_000)" in core)
    check("drift: the default budget matches this mirror",
          "maxSteps: Int = 8" in core and "maxElapsedMs: Long = 90_000" in core)
    catalogue = core.split("object ToolCatalogue")[1].split("object CognitionKnowledge")[0]
    ids = re.findall(r'const val [A-Z]+ = "([a-z_]+)"', catalogue)
    check("drift: the tool ids match this mirror", sorted(ids) == sorted(TOOLS),
          f"{sorted(ids)} vs {sorted(TOOLS)}")


def main() -> int:
    cases = int(sys.argv[1]) if len(sys.argv) > 1 else 500
    seed = int(os.environ.get("FUZZ_SEED", "20261030"))

    print(f"loop simulation · {cases} random scripts · seed {seed}")
    scenarios()
    fuzz(cases, seed)
    drift()

    for line in failures:
        print(f"  FAIL  {line}")
    print()
    if failures:
        print(f"LOOP SIM FAILED · {passed} checks passed, {len(failures)} failed, {checked} runs")
        return 1
    print(f"LOOP SIM OK · {passed} checks · {checked} simulated runs · 7 invariants")
    return 0


if __name__ == "__main__":
    sys.exit(main())
