#!/usr/bin/env python3
"""Executable spec for surface awareness.

Two properties decide whether Lucifer can be trusted when asked about himself:

1. **Recall** — a question about the interface is recognised, every time, from a plain sentence.
2. **Precision** — a question about the *user's task* is not hijacked by it. A false interception is
   worse than letting the model answer with the surface block attached, because the user gets a
   confident, irrelevant lecture about his own UI.

The rules live in `surface/SurfaceAnswers.kt`. This mirrors the classifier and the answer shapes so the
behaviour can be exercised without a JVM:

    python3 tools/surface-core/spec_check.py

`SurfaceCoreTests.kt` asserts the same cases against the real Kotlin.
"""

from __future__ import annotations

import sys

# ── mirror of SurfaceQuestions ────────────────────────────────────────

MAX_QUESTION_CHARS = 220

import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

LIMITS = [
    r"\bwhat can'?t (you|i) do (here|in this|on this|from here)\b",
    r"\b(limits?|limitations?|restrictions?) (here|of this|on this (surface|screen|interface))\b",
    r"\bwhat (are you|is he) (not )?(able|allowed) to do\b",
    r"\bwhy can'?t you (run|see|control|open)\b",
]
DATA = [
    # Anchored: "what do you send *when/to the model/from here*" — not "what do you send in a header".
    r"\bwhat (do you|does he) (send|upload|share|transmit) (when|to the model|to your model|from here|here|on this|to the assistant)\b",
    r"\bwhat (leaves|goes out|is sent) (the device|this device|my phone)\b",
    r"\bare you (spying|watching|recording|tracking)\b",
    r"\b(privacy|data) (here|on this (surface|screen|interface))\b",
    r"\bdoes (this|that) (leak|leave)\b",
]
CONFIRMATION = [
    r"\b(will|do) you (ask|confirm|check) (me )?(before|first)\b",
    r"\bdo i (need to|have to) (confirm|approve)\b",
    r"\bis (it|this) (automatic|autonomous|unattended)\b",
    r"\bcan you run (it|that|this) (without|with no) (asking|confirmation|approval)\b",
]
WHERE = [
    r"\b(where (am i|are we)|which (interface|surface|channel|screen)|what (interface|surface|screen) (is this|am i))\b",
    r"\bwhat am i (talking|speaking) (to|through|from|on)\b",
    r"\bare you (in|on) the (launcher|overlay|terminal|core|app)\b",
]
CAPABILITIES = [
    # Tolerates the hedges people actually type ("actually", "really", "even").
    r"\bwhat can (you|i) (actually |really |even )?do (here|from here|in this|on this|from this|in here)\b",
    r"\bwhat are (your|his) (capabilities|abilities|powers) (here|on this)?\b",
    # Bare form only when nothing follows: "what can you do?" yes, "what can you do about X?" no.
    r"\bwhat (can|could) you (actually |really )?do\s*\??\s*$",
    r"\bcapabilities? (here|on this (surface|screen|interface))\b",
    r"\bwhat (is|does) (this|the) (screen|panel|interface|surface|overlay) (do|let you)\b",
    r"\bcan you (run|execute|open|control|see|read|type|launch) .*\b(here|from here|in this|on this)\b",
    # Articles allowed: "run a shell", "send the command".
    r"\bcan you (run|execute|type|launch|control) (a |an |the )?(commands?|shell|terminal|termux|vm)\b",
]
REASONING = [
    r"\bhow (do|does) (you|he|lucifer) (think|reason|decide|work (it|that) out|figure|check)\b",
    r"\bdo you (use|have) (any )?(tools?|a loop|steps|reasoning)\b",
    r"\bare you (just )?(guessing|making (it|that|things) up|hallucinating|improvising)\b",
    r"\bhow (many )?steps\b",
    r"\bwhat tools (do you|have you|are)\b",
    r"\b(step by step|chain of thought|think harder|go deeper)\b",
    r"\bhow (do|can) you (know|verify|confirm) (that|this|it)\b",
]
LIST = [
    r"\b(what|which) (interfaces?|surfaces?|ways|places) (can i|do i|to)\b",
    r"\bwhere (can|do) i (talk|reach|message) (to )?you\b",
    r"\blist (your )?(interfaces|surfaces)\b",
    r"\ball the ways (i can|to) (talk|reach) (to )?you\b",
]


def _any(patterns, text):
    return any(re.search(p, text, re.IGNORECASE) for p in patterns)


def classify(raw: str) -> str:
    text = raw.strip()
    if not text or len(text) > MAX_QUESTION_CHARS:
        return "NONE"
    looks_like_question = (
        "?" in text
        or any(text.lower().startswith(p) for p in
               ("what", "which", "where", "can", "do ", "list", "tell me"))
    )
    if not looks_like_question:
        return "NONE"
    if _any(LIMITS, text):
        return "LIMITS"
    if _any(DATA, text):
        return "DATA"
    if _any(CONFIRMATION, text):
        return "CONFIRMATION"
    if _any(REASONING, text):
        return "REASONING"
    if _any(WHERE, text):
        return "WHERE"
    if _any(CAPABILITIES, text):
        return "CAPABILITIES"
    if _any(LIST, text):
        return "LIST"
    return "NONE"


# ── the cases ────────────────────────────────────────────────────────

MUST_MATCH = {
    "WHERE": [
        "where am I talking to you from?",
        "which interface is this?",
        "what surface am I on?",
        "what am I talking to you through?",
    ],
    "CAPABILITIES": [
        "what can you do here?",
        "what can you actually do from here",
        "what are your capabilities here?",
        "what does this panel let you do?",
        "can you run commands here?",
        "can you run a shell?",
        "what can you do",
    ],
    "LIMITS": [
        "what can't you do here?",
        "what are the limits here?",
        "why can't you see the terminal?",
        "what are you not allowed to do?",
    ],
    "DATA": [
        "what do you send when I ask you something?",
        "what leaves the device?",
        "are you spying on me?",
        "does this leak anything?",
    ],
    "CONFIRMATION": [
        "will you ask me before running it?",
        "do I need to confirm?",
        "is it automatic?",
        "can you run it without asking me?",
    ],
    "LIST": [
        "what interfaces can I talk to you from?",
        "where can I reach you?",
        "list your interfaces",
    ],
}

# Conversational messages that must NOT be captured as interface questions.
MUST_NOT_MATCH = [
    "what can you do about this failing build?",
    "can you open chrome for me",
    "what does this error mean?",
    "why can't you connect to the VM?",
    "run git status on the VM",
    "explain entropy",
    "the terminal output says permission denied, what now?",
    "what is the weather in Charlotte",
    "can you write a python script that reads a CSV",
    "what do you send in the HTTP headers",
]

passed = 0
failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    global passed
    if condition:
        passed += 1
    else:
        failures.append(f"{name}{(': ' + detail) if detail else ''}")


def case_classifier():
    for kind, messages in MUST_MATCH.items():
        for message in messages:
            got = classify(message)
            check(f"{kind}: {message!r}", got == kind, f"got {got}")
    for message in MUST_NOT_MATCH:
        got = classify(message)
        check(f"not hijacked: {message!r}", got == "NONE", f"got {got}")
    # Guards
    check("empty is NONE", classify("") == "NONE")
    check("very long is NONE", classify("what can you do here? " + "x" * 300) == "NONE")
    check("statement is NONE", classify("the overlay is nice") == "NONE")

    # The reasoning category: how he works is a surface question too, and it must not swallow
    # ordinary questions about reasoning or about tools the user names themselves.
    check("reasoning: 'how do you think?'", classify("how do you think?") == "REASONING")
    check("reasoning: 'do you use tools?'", classify("do you use tools?") == "REASONING")
    check("reasoning: 'are you just guessing?'", classify("are you just guessing?") == "REASONING")
    check("reasoning: 'what tools do you have'", classify("what tools do you have?") == "REASONING")
    check("reasoning: 'step by step'", classify("explain step by step?") == "REASONING")
    check("reasoning: 'how do you check that?'", classify("how do you check that?") == "REASONING")

    # The reasoning answer must be able to show evidence, not just describe the machinery.
    runtime = open(os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer",
                                "mobile", "surface", "SurfaceRuntime.kt"), encoding="utf-8").read()
    answers = open(os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer",
                                "mobile", "surface", "SurfaceAnswers.kt"), encoding="utf-8").read()
    check("reasoning: the answer accepts the real tool record", "toolRecord: String = \"\"" in answers)
    check("reasoning: and passes it to describe()", "record = toolRecord" in answers)
    check("reasoning: with the budget actually in force here",
          "LoopBudget.forSurface(surface.id)" in answers)
    core = open(os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer",
                             "mobile", "cognition", "CognitionCore.kt"), encoding="utf-8").read()
    check("reasoning: the overlay budget is one named thing", "val OVERLAY = LoopBudget(" in core)
    check("reasoning: the overlay surface is on that budget",
          '"overlay-termux", "overlay-vm"' in core)
    check("reasoning: the runtime supplies it from the device", "CognitionScores.report(context)" in runtime)
    check("reasoning: a statement about thinking is NONE",
          classify("thinking about the terminal is hard") == "NONE")
    check("reasoning: confirmation still wins",
          classify("will you confirm before you think?") == "CONFIRMATION")


# ── answer shape ─────────────────────────────────────────────────────

def answer(kind: str, surface_name: str, spec: dict, facts: dict) -> str | None:
    """Mirror of SurfaceAnswers.answer — asserts the required content appears."""
    if kind == "NONE":
        return None
    snapshot = " · ".join(
        [
            "network switch: " + ("ON" if facts.get("networkOnline", True) else "OFF"),
            "model key: " + ("configured" if facts.get("modelKey") else "missing"),
        ]
    )
    if kind == "CAPABILITIES":
        return (
            f"Here is what I can actually do from {surface_name}.\n"
            + "\n".join(f"· {c}" for c in spec["canDo"])
            + "\nWhat I cannot do here:\n"
            + "\n".join(f"· {c}" for c in spec["cannotDo"])
            + f"\nApproval: {spec['confirmation']}\nLive right now: {snapshot}"
        )
    if kind == "LIMITS":
        return (
            f"Limits on {surface_name}:\n"
            + "\n".join(f"· {c}" for c in spec["cannotDo"])
            + f"\nWhy: {spec['limits']}"
        )
    if kind == "DATA":
        return f"Where you are matters for data. On {surface_name}:\n{spec['leavesDevice']}\nLive right now: {snapshot}"
    if kind == "CONFIRMATION":
        return f"On {surface_name}: {spec['confirmation']}"
    if kind == "WHERE":
        return f"You are talking to me from {surface_name}.\n{spec['whatItIs']}\nLive right now: {snapshot}"
    if kind == "LIST":
        return "You can talk to me from these interfaces:"
    return None


SPEC = {
    "canDo": ["Answer questions", "Run a command after confirmation"],
    "cannotDo": ["Run a command without confirmation", "See your screen"],
    "confirmation": "The exact command is shown first.",
    "limits": "No background work.",
    "leavesDevice": "Your message goes to the configured model when the network switch is on.",
    "whatItIs": "The floating chat over Termux.",
}


def case_answers():
    for kind in ["CAPABILITIES", "LIMITS", "DATA", "CONFIRMATION", "WHERE", "LIST"]:
        text = answer(kind, "the floating chat over Termux", SPEC, {"networkOnline": True, "modelKey": True})
        check(f"answer exists for {kind}", text is not None and len(text) > 40)

    caps = answer("CAPABILITIES", "the floating chat over Termux", SPEC, {"networkOnline": True, "modelKey": True})
    check("capabilities lists what it can do", "Run a command after confirmation" in caps)
    check("capabilities lists what it cannot", "See your screen" in caps)
    check("capabilities states the approval rule", "exact command is shown" in caps)
    check("capabilities reports live state", "network switch: ON" in caps)

    offline = answer("CAPABILITIES", "the floating chat over Termux", SPEC, {"networkOnline": False, "modelKey": True})
    check("offline state is reported", "network switch: OFF" in offline)

    check("NONE produces no answer", answer("NONE", "x", SPEC, {}) is None)

    # The honesty clause: awareness is not surveillance.
    check("awareness never claims sight", "See your screen" in caps and "cannot" in caps.lower())


def main() -> int:
    case_classifier()
    case_answers()
    for line in failures:
        print(f"FAIL  {line}")
    print()
    if failures:
        print(f"FAILED · {passed} passed, {len(failures)} failed")
        return 1
    print(f"PASS · {passed} checks (surface awareness spec)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
