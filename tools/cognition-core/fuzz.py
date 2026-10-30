#!/usr/bin/env python3
"""Property-based fuzzing for the cognition scaffold.

`spec_check.py` proves the cases someone thought of. This proves the *invariants* over inputs nobody
thought of: random bytes, pathological whitespace, injection shapes, thousands of appends, zero-step
budgets. The invariants are the ones that matter when a model — not a person — is writing the input:

  I1  the parser never returns a call to a tool that was not offered
  I2  the parser never returns an argument longer than the tool's own cap
  I3  an ANSWER that precedes a TOOL line is never executed as a tool
  I4  working memory never exceeds its capacity, and always keeps the first goal
  I5  the loop guard is monotone: past a budget, it always reports *some* stop reason
  I6  confidence is never HIGH without at least two observations
  I7  a blocked audit is never HIGH or MEDIUM
  I8  triage never says DEEP when there are no tools
  I10 sanitising an observation always removes control-format lines (a fetched page cannot speak the
      protocol)
  I9  no function raises, for any input

Usage:
    python3 tools/cognition-core/fuzz.py            # 5 000 cases per property
    python3 tools/cognition-core/fuzz.py 50000      # a longer run

Exit code is 0 only if every invariant held for every case.
"""

from __future__ import annotations

import os
import random
import re
import string
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import spec_check as spec  # noqa: E402  (the mirror is the implementation under test)

CASES = int(sys.argv[1]) if len(sys.argv) > 1 else 5_000
SEED = int(os.environ.get("FUZZ_SEED", "20261030"))
rng = random.Random(SEED)

failures: list[str] = []
checked = 0


def fail(invariant: str, detail: str) -> None:
    if len(failures) < 40:
        failures.append(f"{invariant} — {detail[:300]}")


TOOL_IDS = {"corpus_search", "memory_search", "device_facts", "clock", "page_read"}
CAPS = {"corpus_search": 200, "memory_search": 200, "device_facts": 40, "clock": 20, "page_read": 400}

ALPHABET = string.printable + "\u00e9\u4e2d\u0000\u202e\ufeff" + "\n\r\t"
SHAPES = [
    "TOOL: {}(",
    "TOOL: {}",
    "ACTION: {}('",
    "CALL: {}(\")",
    "THOUGHT: x\nTOOL: {}(",
    "ANSWER: a\nTOOL: {}(",
    "TOOL: {}(".format("__import__('os')"),
    "TOOL: rm_rf(\"/\")",
    "ANSWER: ",
    "FINAL - ",
    "\x00TOOL: corpus_search(\"x\")\x00",
]

NOTES = ["", "x", " ", "\n", "\u0000", "a" * 5000, ")(\"'\nTOOL: clock(", "\\", "\"\"", "''"]


def random_text() -> str:
    roll = rng.random()
    if roll < 0.35:
        return "".join(rng.choice(ALPHABET) for _ in range(rng.randint(0, 400)))
    if roll < 0.75:
        return rng.choice(SHAPES).format(rng.choice(sorted(TOOL_IDS) + ["rm_rf", "x", ""])) + rng.choice(NOTES)
    return rng.choice(SHAPES)


def case_parser(n: int) -> None:
    global checked
    for _ in range(n):
        raw = random_text()
        try:
            step = spec.parse(raw)
        except Exception as error:  # I9
            fail("I9 parser raised", f"{error.__class__.__name__}: {error}")
            continue
        checked += 1
        tool = step.get("tool")
        if tool is not None and tool != "__unknown__":
            if tool not in TOOL_IDS:  # I1
                fail("I1 unoffered tool executed", f"tool={tool} raw={raw[:120]!r}")
            cap = CAPS.get(tool, 400)
            if len(step.get("argument", "")) > cap:  # I2
                fail("I2 argument exceeded its cap", f"tool={tool} len={len(step.get('argument',''))}")
        if tool == "__unknown__":
            arg = step.get("argument", "")
            if "rm_rf" in arg and "rm_rf" not in raw:
                fail("I1 fabricated a tool argument", arg[:120])
        # I3: an answer marker before a tool line owns the message.
        answer_at = re.search(r"(?im)^\s*(ANSWER|FINAL|RESPONSE)\s*[:\-\u2013]\s*", raw or "")
        tool_at = re.search(r"(?im)^\s*(TOOL|ACTION|CALL)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)\s*$", raw or "")
        if answer_at and tool_at and answer_at.start() < tool_at.start():
            if step.get("tool") is not None:
                fail("I3 executed a tool written inside an answer", raw[:160])


def case_memory(n: int) -> None:
    global checked
    kinds = list(spec.PRIORITY)
    for _ in range(n):
        capacity = rng.randint(8, 24)
        m = spec.Memory(capacity=capacity)
        first_goal = None
        for _ in range(rng.randint(0, 120)):
            kind = rng.choice(kinds)
            text = random_text()[:200] or "x"
            if kind == "GOAL" and first_goal is None and text.strip():
                # Only a goal that was actually accepted can be required to survive: a blank append is
                # ignored by design, and asserting on it would be a test bug, not a finding.
                first_goal = text.strip()
            try:
                m.append(kind, text, rng.choice(["", "corpus", "device"]))
            except Exception as error:  # I9
                fail("I9 memory raised", f"{error.__class__.__name__}")
        checked += 1
        if len(m.entries) > capacity:  # I4
            fail("I4 capacity exceeded", f"{len(m.entries)} > {capacity}")
        if first_goal is not None:
            pinned = sum(1 for e in m.entries if spec.PRIORITY[e[0]] == 0)
            if pinned <= capacity and not any(e[1] == first_goal for e in m.entries):
                fail("I4 the first goal was dropped while pinned entries fit", f"cap={capacity}")
        rendered = m.render(budget=rng.choice([100, 500, 6000]))
        if len(rendered) > 6000 + 2000:
            fail("I4 render ignored its budget", str(len(rendered)))


def case_guard(n: int) -> None:
    global checked
    calls = ["a", "b", "c"]
    for _ in range(n):
        step = rng.randint(0, 20)
        elapsed = rng.randint(0, 200_000)
        observed = rng.randint(0, 40_000)
        seq = [rng.choice(calls) for _ in range(rng.randint(0, 6))]
        obs = [rng.choice(["", "x", "y"]) for _ in range(rng.randint(0, 4))]
        try:
            reason = spec.stop_reason(step, elapsed, calls=seq, observations=obs, observed_chars=observed)
        except Exception as error:  # I9
            fail("I9 guard raised", f"{error.__class__.__name__}")
            continue
        checked += 1
        if elapsed >= 90_000 and reason != "TIME_EXHAUSTED":  # I5
            fail("I5 no stop reason past the time budget", f"elapsed={elapsed} reason={reason}")
        if step >= 8 and elapsed < 90_000 and reason not in ("STEPS_EXHAUSTED", "OBSERVATION_LIMIT", "STALLED"):
            fail("I5 no stop reason past the step budget", f"step={step} reason={reason}")
        if reason is not None and reason not in ("TIME_EXHAUSTED", "STEPS_EXHAUSTED",
                                                 "OBSERVATION_LIMIT", "STALLED"):
            fail("I5 invented a termination", str(reason))


def case_audit(n: int) -> None:
    global checked
    effects = ["NONE", "DEVICE_READ", "NETWORK_READ", "LOCAL_WRITE", "DEVICE_CHANGE", "EXTERNAL_SEND"]
    for _ in range(n):
        goal = random_text()[:300]
        answer = random_text()[:600]
        observations = [random_text()[:300] for _ in range(rng.randint(0, 3))]
        chosen = [rng.choice(effects) for _ in range(rng.randint(0, 2))]
        try:
            result = spec.audit(goal, answer, observations, chosen)
        except Exception as error:  # I9
            fail("I9 audit raised", f"{error.__class__.__name__}")
            continue
        checked += 1
        conf = result.get("confidence")
        non_blank = [o for o in observations if o.strip()]
        if conf == "HIGH" and len(non_blank) < 2:  # I6
            fail("I6 HIGH without two observations", f"obs={len(non_blank)}")
        if "THIN" in result.get("findings", []) and conf != "NONE":  # I7
            fail("I7 a blocked draft was not NONE", conf)
        if conf not in ("NONE", "LOW", "MEDIUM", "HIGH"):
            fail("I7 invented a confidence", str(conf))


def case_triage(n: int) -> None:
    global checked
    for _ in range(n):
        text = random_text()[:800]
        tools = rng.choice([0, 1, 3, 5])
        try:
            result = spec.triage(text, tools)
        except Exception as error:  # I9
            fail("I9 triage raised", f"{error.__class__.__name__}")
            continue
        checked += 1
        if result["depth"] == "DEEP" and tools <= 0:  # I8
            fail("I8 deep with no tools", text[:80])
        if result["depth"] not in ("DEEP", "SHALLOW"):
            fail("I8 invented a depth", str(result["depth"]))
        if not result["reason"]:
            fail("I8 no reason given", text[:60])


def case_fence(n: int) -> None:
    global checked
    for _ in range(n):
        raw = random_text()
        try:
            out = spec.sanitize(raw)
        except Exception as error:  # I9
            fail("I9 sanitize raised", f"{error.__class__.__name__}")
            continue
        checked += 1
        if spec.TOOL_LINE.search(out) or spec.ANSWER_LINE.search(out):  # I10
            fail("I10 a defanged observation still parses as a directive", out[:160])
        if len(out) > 4000:
            fail("I10 sanitiser ignored its cap", str(len(out)))
        if "\x00" in out:
            fail("I10 NUL survived sanitising", out[:60])


def case_roundtrip(n: int) -> None:
    """A model that writes a clean plan must always be understood; nothing valid is ever misread."""
    global checked
    for _ in range(n):
        tool = rng.choice(sorted(TOOL_IDS))
        cap = CAPS[tool]
        arg = "".join(rng.choice(string.ascii_letters + " #.-_/:=?&") for _ in range(rng.randint(1, cap)))
        raw = f'THOUGHT: checking.\nTOOL: {tool}("{arg}")'
        step = spec.parse(raw)
        checked += 1
        if step.get("tool") != tool:
            fail("roundtrip: a well-formed tool line was misread", f"{tool} -> {step.get('tool')}")
        if step.get("argument") != arg.strip():
            fail("roundtrip: the argument was altered", f"{arg[:60]!r} -> {step.get('argument','')[:60]!r}")


def main() -> int:
    print(f"cognition fuzz · seed {SEED} · {CASES} cases per property")
    case_parser(CASES)
    case_memory(max(10, CASES // 10))
    case_guard(CASES)
    case_audit(CASES)
    case_triage(CASES)
    case_fence(CASES)
    case_roundtrip(CASES)

    for line in failures:
        print(f"  FAIL  {line}")
    print()
    if failures:
        print(f"FUZZ FAILED · {len(failures)} invariant breach(es) over {checked} cases")
        return 1
    print(f"FUZZ OK · {checked} cases · 10 invariants held")
    return 0


if __name__ == "__main__":
    sys.exit(main())
