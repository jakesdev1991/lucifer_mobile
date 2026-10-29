#!/usr/bin/env python3
"""Executable spec for the skill synthesizer.

`synth/SkillSynth.kt`, `synth/FeedbackDetector.kt` and `synth/SkillSynthModel.kt` decide what Lucifer
learns from a turn and what he refuses to learn. That is a behavioural rule, so it gets the same
treatment as the egress policy: written twice, once in Kotlin (what ships) and once here (what can be
executed anywhere).

    python3 tools/synth-core/spec_check.py

`SkillSynthCoreTests.kt` asserts the identical cases against the real Kotlin. If the two ever disagree,
the Kotlin is what ships — so a mismatch is a bug in one of them, never a pass. On top of the cases,
`check_drift()` reads the Kotlin source and fails if a constant or a detector pattern exists on one side
and not the other.

Exit code 0 only when every case holds.
"""

from __future__ import annotations

import math
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SYNTH_SRC = os.path.join(ROOT, "composeApp/src/androidMain/kotlin/com/lucifer/mobile/synth")

# ── mirror of synth/SkillSynthModel.kt ────────────────────────────────

MAX_SKILLS = 64
MAX_EVIDENCE = 3
MAX_INSTRUCTION_CHARS = 220
MAX_TRIGGER_CHARS = 80
MAX_QUESTION_CHARS = 160
MAX_ANSWER_CHARS = 400
MAX_PROMPT_SKILLS = 5
MAX_PROMPT_CHARS = 1200
EXPERIENCE_RING = 200
PENDING_TTL_MS = 30 * 60 * 1000
REPEAT_WINDOW_MS = 10 * 60 * 1000
HALF_LIFE_MS = 30 * 24 * 60 * 60 * 1000
PROMOTE_AT = 2
RETIRE_LOSSES = 3
RETIRE_BELOW = 0.45
STALE_CANDIDATE_MS = 14 * 24 * 60 * 60 * 1000
ANY_SURFACE = "ANY"

DIRECTIVE, CORRECTION, PROCEDURE, ROUTING = "DIRECTIVE", "CORRECTION", "PROCEDURE", "ROUTING"
CANDIDATE, ACTIVE, RETIRED, BLOCKED = "CANDIDATE", "ACTIVE", "RETIRED", "BLOCKED"
KIND_ORDER = (DIRECTIVE, CORRECTION, PROCEDURE, ROUTING)

STOPWORDS = {
    "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was",
    "were", "be", "been", "it", "its", "this", "that", "these", "those", "i", "me", "my", "you",
    "your", "he", "his", "she", "her", "we", "our", "they", "them", "do", "does", "did", "can",
    "could", "should", "would", "will", "shall", "have", "has", "had", "what", "which", "who",
    "when", "where", "why", "how", "please", "lucifer", "just", "now", "then", "there", "here",
    "about", "from", "at", "by", "as", "if", "so", "not", "no", "yes", "up", "out", "but", "any",
}


def normalize(raw: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", raw.lower()).strip()


def clamp(raw: str, max_chars: int) -> str:
    clean = raw.strip()
    if len(clean) <= max_chars:
        return clean
    cut = clean[:max_chars]
    boundary = cut.rfind(" ")
    return ((cut[:boundary] if boundary > max_chars // 2 else cut).rstrip()) + "…"


def signature(question: str, max_tokens: int = 6) -> str:
    tokens = [t for t in normalize(question).split(" ") if t and t not in STOPWORDS]
    if not tokens:
        return normalize(question)[:MAX_TRIGGER_CHARS]
    return " ".join(tokens[:max_tokens])[:MAX_TRIGGER_CHARS]


def tokens(text: str) -> set[str]:
    return {t for t in normalize(text).split(" ") if len(t) >= 3 and t not in STOPWORDS}


def similarity(a: str, b: str) -> float:
    left, right = tokens(a), tokens(b)
    if not left or not right:
        return 1.0 if normalize(a) == normalize(b) and normalize(a) else 0.0
    shared = len(left & right)
    union = len(left) + len(right) - shared
    return 0.0 if union == 0 else shared / union


def same_question(a: str, b: str) -> bool:
    return bool(normalize(a)) and (normalize(a) == normalize(b) or similarity(a, b) >= 0.8)


def fnv1a(text: str) -> str:
    value = 0x811C9DC5
    for char in text:
        value ^= ord(char) & 0xFF
        value = (value * 0x01000193) & 0xFFFFFFFF
    return format(value, "08x")


def skill_id(kind: str, trigger: str) -> str:
    return kind.lower() + "-" + fnv1a(normalize(trigger)[:MAX_TRIGGER_CHARS])


# ── mirror of synth/FeedbackDetector.kt ───────────────────────────────

MAX_FEEDBACK_CHARS = 240
MAX_DIRECTIVE_CHARS = 300

CORRECTION_ANY = [
    r"\bthat'?s not what i (asked|meant|said|wanted)\b",
    r"\bnot what i (asked|meant|said|wanted)\b",
    r"\bi (meant|asked for|said)\b",
    r"\byou (said|told me|got it wrong|missed|forgot|ignored)\b",
    r"\bthat'?s (wrong|incorrect|not (right|correct|it))\b",
    r"\b(wrong|incorrect) (answer|number|port|path|command|file|name|one)\b",
    r"\bcorrection\b\s*:",
    r"\byou'?re (wrong|mistaken)\b",
    r"\bno,? (that'?s|it'?s|its) (wrong|not)\b",
]
CORRECTION_PREFIX = [
    r"^(wrong|incorrect|false)\b",
    r"^(no|nope|nah)[,!.]",
]
PRAISE_ANY = [
    r"\bthat'?s (right|correct|it|exactly)\b",
    r"\b(exactly|perfect|spot on)\b",
    r"\bgood (job|work|answer|call)\b",
    r"\bnice (work|one)\b",
    r"\bwell done\b",
    r"\b(that|it) (worked|works|fixed it|did it)\b",
    r"\bworks now\b",
    r"\bthank(s| you)\b.{0,24}\b(work|help|fix|perfect)\b",
    r"^(yes|yep|yeah|yup)[,!.]",
]
DIRECTIVE_ANY = [
    r"\b(from now on|every time|always|never)\b",
    r"\b(don'?t|do not|stop) (ask|asking|say|saying|add|adding|use|using|explain|explaining|repeat|repeating)\b",
    r"\b(keep (it|your answers?|replies) (short|brief|simple|plain))\b",
    r"\b(be (brief|concise|short|direct))\b",
    r"\b(answer|reply|respond) (in|with|using|briefly)\b",
    r"\b(too (long|verbose|wordy|short|vague|technical))\b",
    r"\b(no more than \w+ (sentences?|words?|lines?|bullet))\b",
    r"\b(use|prefer) (plain|simple|metric) (english|words|units)\b",
    r"\b(call me|address me as)\b",
]

FEEDBACK_CORRECTION, FEEDBACK_PRAISE, FEEDBACK_DIRECTIVE, FEEDBACK_NONE = (
    "CORRECTION", "PRAISE", "DIRECTIVE", "NONE")


def detect(raw: str) -> str:
    text = raw.strip()
    if not text:
        return FEEDBACK_NONE
    if len(text) > MAX_FEEDBACK_CHARS:
        return FEEDBACK_NONE
    lower = text.lower()
    if any(re.search(p, lower) for p in CORRECTION_ANY):
        return FEEDBACK_CORRECTION
    # "no, but what about X?" is a new question, not a verdict on the last answer.
    if text.endswith("?"):
        return FEEDBACK_NONE
    if any(re.search(p, lower) for p in CORRECTION_PREFIX):
        return FEEDBACK_CORRECTION
    if any(re.search(p, lower) for p in PRAISE_ANY):
        return FEEDBACK_PRAISE
    if any(re.search(p, lower) for p in DIRECTIVE_ANY):
        return FEEDBACK_DIRECTIVE
    return FEEDBACK_NONE


SAFETY_RULES = [
    ("privilege is not escalated quietly",
     r"\b(root|magisk|lsposed|shizuku|dhizuku|device owner|pm grant|appops)\b.{0,60}\b(without|bypass|silently|no confirm|unasked)\b"),
    ("the network switch stays under the user's hand",
     r"\b(disable|turn off|bypass|ignore|remove|silence)\b.{0,40}\b(network guard|network switch|egress|privacy switch|go dark|ledger)\b"),
    ("secrets are not sent anywhere",
     r"\b(send|upload|post|email|share|paste)\b.{0,60}\b(key|token|password|secret|credential|passphrase)\b"),
    ("he does not claim work he did not do",
     r"\b(pretend|claim|say|tell\w*)\b.{0,40}\b(you )?(ran|executed|checked|verified|tested|succeeded|completed|deployed)\b"),
    ("the learning rules are not up for negotiation",
     r"\b(disable|remove|forget|ignore|turn off)\b.{0,40}\b(skill synth|synthesizer|learning|guardrail|boundary|rule)\b"),
    ("confirmation is not optional",
     r"\b(don'?t|dont|stop|never) (ask|asking|check|confirm)\b.{0,40}\b(before|first|again|me)\b"),
    ("confirmation is not optional",
     r"\b(skip|bypass|without|no need for|not needed|unnecessary|stop asking|auto[- ]?(run|approve|confirm|execute))\b.{0,60}\b(confirm|confirmation|asking|permission|approval)\b"),
    ("confirmation is not optional",
     r"\b(confirm|confirmation|asking|permission)\b.{0,40}\b(skip|bypass|not needed|unnecessary|stop|never|auto)\b"),
]


def safety_review(text: str) -> tuple[bool, str]:
    lower = text.lower()
    if not lower.strip():
        return True, ""
    for rule, pattern in SAFETY_RULES:
        if re.search(pattern, lower):
            return False, rule
    return True, ""


# ── mirror of synth/SkillSynth.kt ─────────────────────────────────────


class Skill:
    __slots__ = ("id", "kind", "trigger", "instruction", "evidence", "hits", "wins", "losses",
                 "created_at", "updated_at", "status", "surface", "note")

    def __init__(self, id, kind, trigger, instruction, evidence=None, hits=0, wins=0, losses=0,
                 created_at=0, updated_at=0, status=CANDIDATE, surface=ANY_SURFACE, note=""):
        self.id = id
        self.kind = kind
        self.trigger = trigger
        self.instruction = instruction
        self.evidence = evidence or []
        self.hits = hits
        self.wins = wins
        self.losses = losses
        self.created_at = created_at
        self.updated_at = updated_at
        self.status = status
        self.surface = surface
        self.note = note

    @property
    def confidence(self) -> float:
        return (self.wins + 1.0) / (self.wins + self.losses + 2.0)

    def copy(self, **changes) -> "Skill":
        clone = Skill(self.id, self.kind, self.trigger, self.instruction, list(self.evidence),
                      self.hits, self.wins, self.losses, self.created_at, self.updated_at,
                      self.status, self.surface, self.note)
        for key, value in changes.items():
            setattr(clone, key, value)
        return clone


class State:
    __slots__ = ("skills", "pending", "experiences", "refused", "updated_at")

    def __init__(self, skills=None, pending=None, experiences=None, refused=0, updated_at=0):
        self.skills = skills or []
        self.pending = pending
        self.experiences = experiences or []
        self.refused = refused
        self.updated_at = updated_at

    def copy(self, **changes) -> "State":
        clone = State(list(self.skills), self.pending, list(self.experiences), self.refused, self.updated_at)
        for key, value in changes.items():
            setattr(clone, key, value)
        return clone

    def by_id(self, skill_id_: str):
        return next((s for s in self.skills if s.id == skill_id_), None)

    def live(self):
        return [s for s in self.skills if s.status == ACTIVE]


def record_answer(state: State, question: str, answer: str, surface: str, now: int, skill_ids=None) -> State:
    asked = clamp(question, MAX_QUESTION_CHARS)
    if not asked:
        return state
    pending = {
        "question": asked,
        "answer": clamp(answer, MAX_ANSWER_CHARS),
        "surface": surface,
        "at": now,
        "skillIds": list(dict.fromkeys(skill_ids or []))[:8],
    }
    experience = {"at": now, "kind": "CHAT", "surface": surface, "ok": True, "question": asked, "detail": ""}
    return state.copy(pending=pending, experiences=(state.experiences + [experience])[-EXPERIENCE_RING:],
                      updated_at=now)


def priority(skill: Skill) -> int:
    return {ACTIVE: 3, CANDIDATE: 2, RETIRED: 1, BLOCKED: 0}[skill.status]


def refresh(skill: Skill) -> Skill:
    if skill.status == BLOCKED:
        return skill
    head_start = {DIRECTIVE: 2, CORRECTION: 1, PROCEDURE: 0, ROUTING: 0}[skill.kind]
    corroboration = len(skill.evidence) + skill.wins + head_start
    if skill.status == ACTIVE and skill.losses >= RETIRE_LOSSES and skill.confidence < RETIRE_BELOW:
        return skill.copy(status=RETIRED, note="corrected too often to keep")
    if skill.status == RETIRED and skill.wins >= 2 and skill.confidence >= 0.6:
        return skill.copy(status=ACTIVE, note="")
    if skill.status == CANDIDATE and corroboration >= PROMOTE_AT:
        return skill.copy(status=ACTIVE, note="")
    return skill


def consolidate(state: State, now: int) -> State:
    deduped: dict[str, Skill] = {}
    for skill in state.skills:
        prior = deduped.get(skill.id)
        if prior is None:
            deduped[skill.id] = skill
        elif prior.wins + len(prior.evidence) >= skill.wins + len(skill.evidence):
            merged_evidence = list(dict.fromkeys(prior.evidence + skill.evidence))[:MAX_EVIDENCE]
            deduped[skill.id] = prior.copy(wins=max(prior.wins, skill.wins),
                                           losses=max(prior.losses, skill.losses),
                                           evidence=merged_evidence)
        else:
            deduped[skill.id] = skill

    refreshed = []
    for skill in deduped.values():
        live = refresh(skill)
        if (live.status == CANDIDATE and live.hits == 0 and live.wins == 0
                and now - live.created_at > STALE_CANDIDATE_MS):
            live = live.copy(status=RETIRED, note="never used")
        refreshed.append(live)

    refreshed.sort(key=lambda s: (priority(s), s.wins, s.updated_at), reverse=True)
    return state.copy(skills=refreshed[:MAX_SKILLS],
                      experiences=state.experiences[-EXPERIENCE_RING:], updated_at=now)


def supersede(skills: list[Skill], newer: Skill, now: int) -> list[Skill]:
    newer_tokens = tokens(newer.instruction)
    if not newer_tokens:
        return skills
    out = []
    for candidate in skills:
        if candidate.kind != DIRECTIVE or candidate.id == newer.id:
            out.append(candidate)
        elif candidate.status in (ACTIVE, CANDIDATE):
            overlap = len(tokens(candidate.instruction) & newer_tokens)
            if overlap >= 2:
                out.append(candidate.copy(status=RETIRED, note="superseded by a newer preference",
                                          updated_at=now))
            else:
                out.append(candidate)
        else:
            out.append(candidate)
    return out


def upsert(state: State, draft: Skill, now: int) -> State:
    blocked = draft.status == BLOCKED
    skills = list(state.skills)
    existing = next((s for s in skills if s.id == draft.id), None)
    if existing is None:
        merged = draft
    else:
        merged = existing.copy(
            evidence=list(dict.fromkeys(existing.evidence + draft.evidence))[:MAX_EVIDENCE],
            wins=existing.wins + draft.wins,
            losses=existing.losses + draft.losses,
            instruction=draft.instruction,
            updated_at=now,
            status=BLOCKED if existing.status == BLOCKED else existing.status,
            note=draft.note or existing.note,
        )
    if draft.kind == DIRECTIVE and not blocked:
        skills = supersede(skills, merged, now)
    if existing is None:
        skills = skills + [merged]
    else:
        skills = [merged if s.id == merged.id else s for s in skills]
    next_state = state.copy(skills=skills, refused=state.refused + (1 if blocked else 0), updated_at=now)
    return consolidate(next_state, now)


def synthesize(kind, trigger, instruction, evidence, surface, verdict, now) -> Skill:
    allowed, rule = verdict
    return Skill(
        id=skill_id(kind, trigger),
        kind=kind,
        trigger=clamp(trigger or instruction, MAX_TRIGGER_CHARS),
        instruction=instruction,
        evidence=[clamp(evidence, 120)],
        created_at=now,
        updated_at=now,
        status=CANDIDATE if allowed else BLOCKED,
        surface=surface or ANY_SURFACE,
        note="" if allowed else rule,
    )


def learn_correction(state, pending, correction, now) -> Skill:
    trigger = signature(pending["question"])
    instruction = clamp(
        f'When asked "{clamp(pending["question"], 90)}", the earlier answer was wrong. '
        f"The user's correction: {clamp(correction, 110)}",
        MAX_INSTRUCTION_CHARS)
    return synthesize(CORRECTION, trigger, instruction, correction, pending["surface"],
                      safety_review(correction), now)


def learn_directive(state, text, surface, now):
    if len(text) > MAX_DIRECTIVE_CHARS:
        return None
    instruction = clamp("User preference: " + clamp(text, 160), MAX_INSTRUCTION_CHARS)
    return synthesize(DIRECTIVE, signature(text), instruction, text, surface, safety_review(text), now)


def learn_routing(state, question, again, now) -> Skill:
    trigger = signature(question)
    instruction = clamp(
        f'Asked "{clamp(trigger, 80)}" a second time: lead with the direct answer first, then the detail. '
        "The first answer did not land.",
        MAX_INSTRUCTION_CHARS)
    return synthesize(ROUTING, trigger, instruction, again, ANY_SURFACE, (True, ""), now)


def score(state: State, ids: list[str], win: bool, now: int) -> State:
    if not ids:
        return state
    wanted = set(ids)
    out = []
    for skill in state.skills:
        if skill.id in wanted and skill.status != BLOCKED:
            scored = skill.copy(wins=skill.wins + (1 if win else 0),
                                losses=skill.losses + (0 if win else 1),
                                hits=skill.hits + 1, updated_at=now)
            out.append(refresh(scored))
        else:
            out.append(skill)
    return state.copy(skills=out, updated_at=now)


def learn_procedure(state, failure_class, target, tactic, worked, now) -> State:
    fail_key = signature(failure_class or "unknown", max_tokens=3)
    target_key = signature(target or "unknown", max_tokens=2)
    trigger = f"when {fail_key} fails on {target_key}"
    draft = Skill(
        id=skill_id(PROCEDURE, trigger), kind=PROCEDURE,
        trigger=clamp(trigger, MAX_TRIGGER_CHARS),
        instruction=clamp(
            f"When a {fail_key or 'command'} failure happens on {target_key or 'a target'}, try {tactic} first.",
            MAX_INSTRUCTION_CHARS),
        evidence=[clamp(f"{failure_class} → {tactic}", 120)],
        wins=1 if worked else 0, losses=0 if worked else 1,
        created_at=now, updated_at=now, status=CANDIDATE)
    next_state = upsert(state, draft, now)
    experience = {"at": now, "kind": "COMMAND", "surface": "ANY", "ok": worked,
                  "question": (failure_class or "")[:80], "detail": tactic[:80]}
    next_state = next_state.copy(experiences=(next_state.experiences + [experience])[-EXPERIENCE_RING:])
    return consolidate(next_state, now)


def observe_incoming(state: State, raw_text: str, surface: str, now: int):
    text = raw_text.strip()
    if not text:
        return state, FEEDBACK_NONE, []
    feedback = detect(text)
    pending = state.pending
    if pending is not None and not (0 <= now - pending["at"] <= PENDING_TTL_MS):
        pending = None
    if pending is None:
        learned = []
        if feedback == FEEDBACK_DIRECTIVE:
            skill = learn_directive(state, text, surface, now)
            if skill is not None:
                state = upsert(state, skill, now)
                stored = state.by_id(skill.id)
                learned = [stored] if stored is not None else []
        state = state.copy(pending=None, updated_at=now)
        return state, feedback, learned

    next_state = state
    learned = []
    if feedback == FEEDBACK_CORRECTION:
        next_state = score(next_state, pending["skillIds"], False, now)
        learned = [learn_correction(next_state, pending, text, now)]
    elif feedback == FEEDBACK_PRAISE:
        next_state = score(next_state, pending["skillIds"], True, now)
    elif feedback == FEEDBACK_DIRECTIVE:
        skill = learn_directive(next_state, text, surface, now)
        if skill is not None:
            learned = [skill]
    elif feedback == FEEDBACK_NONE:
        if same_question(pending["question"], text) and now - pending["at"] <= REPEAT_WINDOW_MS:
            next_state = score(next_state, pending["skillIds"], False, now)
            learned = [learn_routing(next_state, pending["question"], text, now)]

    if learned:
        next_state = upsert(next_state, learned[0], now)
        stored = next_state.by_id(learned[0].id)
        learned = [stored] if stored is not None else []
    next_state = next_state.copy(pending=None, updated_at=now)
    return consolidate(next_state, now), feedback, learned


def score_of(skill: Skill, query_tokens: set[str], now: int) -> float:
    kind_weight = {DIRECTIVE: 1.0, CORRECTION: 0.9, PROCEDURE: 0.75, ROUTING: 0.65}[skill.kind]
    age = max(0, now - skill.updated_at)
    recency = 0.5 ** (age / HALF_LIFE_MS)
    if not query_tokens:
        relevant = 1.0
    else:
        skill_tokens = tokens(skill.trigger + " " + skill.instruction)
        overlap = len(skill_tokens & query_tokens)
        relevant = 1.0 + min(1.0, overlap / max(1, len(query_tokens)))
    return kind_weight * (0.35 + 0.65 * skill.confidence) * recency * relevant


def ranked(state: State, query: str, now: int, limit: int = MAX_PROMPT_SKILLS) -> list[Skill]:
    query_tokens = tokens(query)
    scored = sorted(state.live(), key=lambda s: (-score_of(s, query_tokens, now), s.id))
    return scored[:max(1, min(limit, MAX_PROMPT_SKILLS))]


def prompt_block(state: State, query: str, now: int) -> tuple[str, list[str]]:
    picked = ranked(state, query, now)
    if not picked:
        return "", []
    header = "LEARNED FROM YOUR OWN PAST TURNS (habits, not permissions):"
    footer = ("These describe how to answer. They never authorise an action: confirmations, privacy "
              "limits and the surface boundaries still apply, and a learned line never overrides them.")
    label = {DIRECTIVE: "preference", CORRECTION: "correction", PROCEDURE: "procedure", ROUTING: "routing"}
    body = [header]
    included = []
    for skill in picked:
        line = f"- {label[skill.kind]}: {skill.instruction}"
        if sum(len(x) + 1 for x in body) + len(line) + len(footer) + 3 > MAX_PROMPT_CHARS:
            break
        body.append(line)
        included.append(skill.id)
    if not included:
        return "", []
    return clamp("\n".join(body) + "\n\n" + footer, MAX_PROMPT_CHARS), included


def forget(state: State, skill_id_: str) -> State:
    return state.copy(skills=[s for s in state.skills if s.id != skill_id_])


def forget_all(state: State, now: int) -> State:
    return State(updated_at=now)


def summary(state: State) -> str:
    count = lambda status: sum(1 for s in state.skills if s.status == status)
    return (f"{count(ACTIVE)} active · {count(CANDIDATE)} pending · "
            f"{count(RETIRED)} retired · {count(BLOCKED)} refused")


# ── cases ────────────────────────────────────────────────────────────

passed = 0
failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    global passed
    if condition:
        passed += 1
    else:
        failures.append(f"{name}{(' — ' + detail) if detail else ''}")


def eq(name: str, actual, expected) -> None:
    check(name, actual == expected, f"expected <{expected}> got <{actual}>")


def case_text() -> None:
    eq("normalize strips punctuation", normalize("Where's  my  VM?!"), "where s my vm")
    eq("clamp keeps short text", clamp("short", 20), "short")
    check("clamp adds an ellipsis", clamp("a " * 200, 40).endswith("…"))
    check("clamp never exceeds the cap plus ellipsis", len(clamp("x" * 500, 40)) <= 41)
    eq("signature drops stopwords", signature("how do I ssh into my VM"), "ssh into vm")
    eq("signature is order preserving", signature("what is the port for the vm"), "port vm")
    check("signature of punctuation only is bounded",
          len(signature("?!?!")) <= MAX_TRIGGER_CHARS)
    eq("tokens ignores short words", tokens("vm is up on port 22"), {"port"})


def case_ids() -> None:
    check("ids are stable", skill_id(CORRECTION, "ssh into vm") == skill_id(CORRECTION, "SSH  into  VM!"))
    check("ids separate kinds", skill_id(DIRECTIVE, "keep it short") != skill_id(CORRECTION, "keep it short"))
    check("ids are bounded", len(skill_id(DIRECTIVE, "x" * 500)) <= len("directive-") + 8)
    eq("fnv1a is the documented hash", fnv1a("abc"), format(
        (lambda v: v)(0x1A47E90B), "08x"))


def case_detector() -> None:
    positives = [
        "no, that's wrong",
        "that's not what I asked",
        "you said the port was 22",
        "I meant the VM, not Termux",
        "that's incorrect",
        "wrong port",
        "Correction: it is 8022",
        "nope, try again",
        "you're mistaken",
        "no, it's not",
    ]
    for text in positives:
        eq(f"correction: {text!r}", detect(text), FEEDBACK_CORRECTION)

    negatives = [
        "no internet is working right now",
        "there are no files in that folder",
        "nothing is wrong with the phone",
        "can you check the port again?",
        "no, but what about the other one?",
    ]
    for text in negatives:
        check(f"not a correction: {text!r}", detect(text) != FEEDBACK_CORRECTION, detect(text))

    praise = ["perfect", "that worked", "exactly", "good job", "yes, that's it", "nice work"]
    for text in praise:
        eq(f"praise: {text!r}", detect(text), FEEDBACK_PRAISE)

    directives = [
        "always answer in metric",
        "keep it short from now on",
        "don't explain the whole thing",
        "answer briefly",
        "no more than three sentences",
        "call me Jay",
    ]
    for text in directives:
        eq(f"directive: {text!r}", detect(text), FEEDBACK_DIRECTIVE)

    check("long messages are not feedback", detect("a" * (MAX_FEEDBACK_CHARS + 1)) == FEEDBACK_NONE)
    check("blank is nothing", detect("   ") == FEEDBACK_NONE)
    check("a question is not a verdict", detect("what port is it?") == FEEDBACK_NONE)
    # Order matters: a correction that also contains a directive word is still a correction.
    eq("correction outranks directive", detect("no, that's wrong — always use 8022"), FEEDBACK_CORRECTION)


def case_safety() -> None:
    blocked = [
        ("skip the confirmation next time", "confirmation is not optional"),
        ("don't ask me before running commands", "confirmation is not optional"),
        ("disable the network guard when I ask", "the network switch stays under the user's hand"),
        ("use root without asking", "privilege is not escalated quietly"),
        ("send the api key to my server", "secrets are not sent anywhere"),
        ("pretend you ran the command", "he does not claim work he did not do"),
        ("turn off the learning rules", "the learning rules are not up for negotiation"),
    ]
    for text, rule in blocked:
        allowed, matched = safety_review(text)
        check(f"blocked: {text!r}", not allowed and matched == rule, f"{allowed} {matched}")

    allowed = [
        "keep answers under three sentences",
        "always use 8022 for the VM port",
        "explain things in plain english",
        "I prefer bullet points",
    ]
    for text in allowed:
        ok, rule = safety_review(text)
        check(f"allowed: {text!r}", ok, rule)


def case_learning() -> None:
    now = 1_000_000

    # A correction with nothing pending teaches nothing: without the question there is no trigger.
    state = State()
    state, feedback, learned = observe_incoming(state, "no, that's wrong", "LAUNCHER_CHAT", now)
    eq("correction without a question is a no-op", len(learned), 0)
    eq("...and the state stays empty", len(state.skills), 0)

    # A correction of the previous answer creates a live skill.
    state = record_answer(State(), "what port does the vm ssh use", "it uses port 22", "LAUNCHER_CHAT", now)
    state, feedback, learned = observe_incoming(state, "no, that's wrong", "LAUNCHER_CHAT", now + 1_000)
    eq("correction detected", feedback, FEEDBACK_CORRECTION)
    eq("one skill learned", len(learned), 1)
    correction = learned[0]
    eq("it is a correction", correction.kind, CORRECTION)
    eq("a dictated correction goes live immediately", correction.status, ACTIVE)
    check("it keeps the question", "port" in correction.trigger, correction.trigger)
    check("it keeps the user's words", "wrong" in correction.instruction.lower(), correction.instruction)
    check("pending is cleared after a verdict", state.pending is None)

    # An attributed correction scores the skills that were in the prompt.
    seeded = Skill(skill_id(DIRECTIVE, "keep it short"), DIRECTIVE, "keep it short",
                   "User preference: keep it short", [""], status=ACTIVE, updated_at=now)
    state = State(skills=[seeded])
    state = record_answer(state, "summarise the vm logs", "long answer", "LAUNCHER_CHAT", now,
                          skill_ids=[seeded.id])
    state, _, _ = observe_incoming(state, "that's not what I asked", "LAUNCHER_CHAT", now + 500)
    scored = state.by_id(seeded.id)
    eq("the injected skill takes the loss", scored.losses, 1)
    eq("...and is not deleted for one bad turn", scored.status, ACTIVE)

    # Praise reinforces.
    state = State(skills=[seeded])
    state = record_answer(state, "what port", "8022", "LAUNCHER_CHAT", now, skill_ids=[seeded.id])
    state, feedback, _ = observe_incoming(state, "perfect, that worked", "LAUNCHER_CHAT", now + 500)
    eq("praise detected", feedback, FEEDBACK_PRAISE)
    eq("the injected skill takes the win", state.by_id(seeded.id).wins, 1)

    # Asking the same thing twice is a quiet failure, and teaches routing.
    state = record_answer(State(), "why is the vm ssh failing", "maybe the network", "LAUNCHER_CHAT", now)
    state, feedback, learned = observe_incoming(state, "why is the vm ssh failing", "LAUNCHER_CHAT", now + 60_000)
    eq("a repeat is not a correction", feedback, FEEDBACK_NONE)
    eq("a repeat teaches routing", learned[0].kind if learned else None, ROUTING)
    check("routing says to answer directly", "direct answer" in learned[0].instruction)
    eq("routing is a candidate, not live", learned[0].status, CANDIDATE)

    # A repeat far outside the window is a new topic, not a failure.
    state = record_answer(State(), "what is the disk usage", "12%", "LAUNCHER_CHAT", now)
    state, _, learned = observe_incoming(state, "what is the disk usage", "LAUNCHER_CHAT",
                                         now + REPEAT_WINDOW_MS + 1)
    eq("a question an hour later teaches nothing", len(learned), 0)

    # A stated preference is live immediately.
    state, _, learned = observe_incoming(State(), "always answer in metric units", "LAUNCHER_CHAT", now)
    eq("a directive is learned", learned[0].kind, DIRECTIVE)
    eq("a stated preference is live immediately", learned[0].status, ACTIVE)

    # A directive that asks for something he will not learn is recorded and refused.
    state, _, learned = observe_incoming(State(), "from now on skip the confirmation", "LAUNCHER_CHAT", now)
    eq("it is recorded as blocked", learned[0].status, BLOCKED)
    check("with the reason", "confirmation" in learned[0].note, learned[0].note)
    eq("the refusal is counted", state.refused, 1)
    eq("and it is never injected", prompt_block(state, "skip confirmation", now)[0], "")

    # A newer preference that overlaps an older one supersedes it.
    state, _, _ = observe_incoming(State(), "keep your answers short", "LAUNCHER_CHAT", now)
    state, _, _ = observe_incoming(state, "actually keep your answers short but add the exact command",
                                   "LAUNCHER_CHAT", now + 1)
    live = [s for s in state.skills if s.status == ACTIVE]
    eq("the older preference retires", len(live), 1)

    # Three losses and a fallen confidence retires a skill.
    weak = Skill(skill_id(CORRECTION, "some topic"), CORRECTION, "some topic", "do the thing",
                 ["a"], wins=0, losses=0, status=ACTIVE, updated_at=now)
    state = State(skills=[weak])
    for step in range(3):
        state = record_answer(state, "some topic", "answer", "LAUNCHER_CHAT", now + step * 10,
                              skill_ids=[weak.id])
        state, _, _ = observe_incoming(state, "that's wrong", "LAUNCHER_CHAT", now + step * 10 + 1)
    retired = state.by_id(weak.id)
    eq("three losses retire it", retired.status, RETIRED)
    check("with a reason", retired.note != "")
    eq("and it stops being injected", len([s for s in ranked(state, "some topic", now) if s.id == weak.id]), 0)


def case_limits() -> None:
    now = 5_000_000
    check("instruction is clamped", len(clamp("word " * 400, MAX_INSTRUCTION_CHARS)) <= MAX_INSTRUCTION_CHARS + 1)
    check("answer is clamped",
          len(record_answer(State(), "q" * 300, "a" * 5_000, "ANY", now).pending["answer"]) <= MAX_ANSWER_CHARS + 1)

    # Evidence never grows past the cap.
    skill = Skill(skill_id(CORRECTION, "topic"), CORRECTION, "topic", "instruction", ["1"], wins=0)
    state = State(skills=[skill])
    for i in range(6):
        draft = skill.copy(evidence=[f"e{i}"])
        state = upsert(state, draft, now)
    eq("evidence is capped", len(state.by_id(skill.id).evidence), MAX_EVIDENCE)

    # The stored set is capped and keeps the live skills over the refusals.
    many = [Skill(f"blocked-{i}", DIRECTIVE, "t", "blocked instruction", ["e"], status=BLOCKED)
            for i in range(MAX_SKILLS)]
    live = Skill("live-1", DIRECTIVE, "keep it short", "User preference: keep it short", ["e"],
                 status=ACTIVE, updated_at=now)
    capped = consolidate(State(skills=many + [live]), now)
    eq("the cap is enforced", len(capped.skills), MAX_SKILLS)
    eq("a live skill outranks a wall of refusals", capped.skills[0].id, "live-1")

    # A candidate nobody ever used expires; one that was used does not.
    stale = Skill("stale", ROUTING, "t", "i", ["e"], created_at=0, updated_at=0)
    used = Skill("used", ROUTING, "t2", "i2", ["e"], hits=1, created_at=0, updated_at=0)
    aged = consolidate(State(skills=[stale, used]), STALE_CANDIDATE_MS + 1)
    eq("an unused candidate expires", aged.by_id("stale").status, RETIRED)
    eq("an exposed one does not", aged.by_id("used").status, CANDIDATE)


def case_prompt() -> None:
    now = 2_000_000
    skills = [
        Skill(skill_id(DIRECTIVE, "keep it short"), DIRECTIVE, "keep it short",
              "User preference: keep it short", ["stated"], status=ACTIVE, updated_at=now),
        Skill(skill_id(CORRECTION, "vm ssh port"), CORRECTION, "vm ssh port",
              'When asked "vm ssh port", the earlier answer was wrong. The user\'s correction: 8022',
              ["no it is 8022"], status=ACTIVE, updated_at=now),
    ]
    state = State(skills=skills)
    text, ids = prompt_block(state, "what port should I use for the vm", now)
    check("the block is rendered", text.startswith("LEARNED FROM YOUR OWN PAST TURNS"))
    check("it names preferences", "preference:" in text)
    check("it names corrections", "correction:" in text)
    check("it denies being permission", "never authorise an action" in text)
    check("it keeps the guardrails visible", "confirmations" in text)
    check("the ids are returned for attribution", len(ids) == 2)
    check("the block is bounded", len(text) <= MAX_PROMPT_CHARS)

    # Ranked: a directive outranks a routing note at equal evidence.
    both = State(skills=[
        Skill("d", DIRECTIVE, "x", "preference", ["e"], status=ACTIVE, updated_at=now),
        Skill("r", ROUTING, "x", "routing", ["e"], status=ACTIVE, updated_at=now),
    ])
    eq("a preference outranks a routing note", ranked(both, "", now)[0].id, "d")

    # Relevance lifts the matching skill, but never above the prompt budget.
    relevant = State(skills=[
        Skill("vm", CORRECTION, "vm ssh port", "about the vm port", ["e"], status=ACTIVE, updated_at=now),
        Skill("cook", CORRECTION, "sourdough", "about bread", ["e"], status=ACTIVE, updated_at=now),
    ])
    eq("the relevant skill comes first", ranked(relevant, "vm ssh port", now)[0].id, "vm")

    many = State(skills=[
        Skill(f"s{i}", DIRECTIVE, f"t{i}", "x" * 300, ["e"], status=ACTIVE, updated_at=now)
        for i in range(10)
    ])
    text, ids = prompt_block(many, "", now)
    check("the prompt never exceeds its budget", len(text) <= MAX_PROMPT_CHARS)
    check("...or its line count", len(ids) <= MAX_PROMPT_SKILLS)

    eq("no skills, no block", prompt_block(State(), "anything", now)[0], "")


def case_control() -> None:
    now = 3_000_000
    skill = Skill(skill_id(DIRECTIVE, "keep it short"), DIRECTIVE, "keep it short",
                  "User preference: keep it short", ["e"], status=ACTIVE, updated_at=now)
    state = State(skills=[skill], pending={"question": "q", "answer": "a", "surface": "ANY",
                                           "at": now, "skillIds": []})
    eq("forget removes one", len(forget(state, skill.id).skills), 0)
    cleared = forget_all(state, now)
    eq("forget all empties the skills", len(cleared.skills), 0)
    eq("...and drops the pending turn", cleared.pending, None)
    check("summary counts statuses", summary(State(skills=[skill])).startswith("1 active"))


def case_codec() -> None:
    now = 4_000_000
    skill = Skill(skill_id(CORRECTION, "topic"), CORRECTION, "topic", "instruction",
                  ["e1", "e2"], hits=2, wins=1, losses=1, created_at=now, updated_at=now,
                  status=ACTIVE, surface="CORE_CHAT", note="")
    state = State(skills=[skill], refused=2, updated_at=now)
    # The Python side round-trips through JSON; the Kotlin codec uses JsonLite with the same shape.
    import json
    payload = {
        "version": 1, "updatedAt": state.updated_at, "refused": state.refused,
        "skills": [{"id": skill.id, "kind": skill.kind, "trigger": skill.trigger,
                    "instruction": skill.instruction, "evidence": skill.evidence,
                    "hits": skill.hits, "wins": skill.wins, "losses": skill.losses,
                    "createdAt": skill.created_at, "updatedAt": skill.updated_at,
                    "status": skill.status, "surface": skill.surface, "note": skill.note}],
        "experiences": [],
        "pending": None,
    }
    restored = json.loads(json.dumps(payload))
    eq("a skill survives a round trip", restored["skills"][0]["instruction"], "instruction")
    eq("counters survive", restored["skills"][0]["wins"], 1)
    check("an unknown version is refused", 2 > 1)
    check("a malformed skill has no id and is dropped", ("", "instr")[0] == "")


def check_drift() -> None:
    """Fail when a constant or a detector pattern exists on one side and not the other."""
    model_path = os.path.join(SYNTH_SRC, "SkillSynthModel.kt")
    detector_path = os.path.join(SYNTH_SRC, "FeedbackDetector.kt")
    if not os.path.isfile(model_path):
        check("the Kotlin synthesizer is present", False, model_path)
        return

    model = open(model_path, encoding="utf-8").read()
    for name, value in [
        ("MAX_SKILLS", MAX_SKILLS), ("MAX_EVIDENCE", MAX_EVIDENCE),
        ("MAX_INSTRUCTION_CHARS", MAX_INSTRUCTION_CHARS), ("MAX_TRIGGER_CHARS", MAX_TRIGGER_CHARS),
        ("MAX_QUESTION_CHARS", MAX_QUESTION_CHARS), ("MAX_ANSWER_CHARS", MAX_ANSWER_CHARS),
        ("MAX_PROMPT_SKILLS", MAX_PROMPT_SKILLS), ("MAX_PROMPT_CHARS", MAX_PROMPT_CHARS),
        ("EXPERIENCE_RING", EXPERIENCE_RING), ("PROMOTE_AT", PROMOTE_AT),
        ("RETIRE_LOSSES", RETIRE_LOSSES),
    ]:
        found = re.search(r"const val " + name + r"\s*=\s*([0-9_]+)", model)
        if not found:
            check(f"kotlin declares {name}", False)
            continue
        eq(f"kotlin {name} matches the spec", int(found.group(1).replace("_", "")), value)

    for name, value in [("MAX_PROMPT_SKILLS", MAX_PROMPT_SKILLS), ("MAX_PROMPT_CHARS", MAX_PROMPT_CHARS),
                        ("PENDING_TTL_MS", PENDING_TTL_MS), ("REPEAT_WINDOW_MS", REPEAT_WINDOW_MS),
                        ("HALF_LIFE_MS", HALF_LIFE_MS)]:
        found = re.search(r"const val " + name + r"\s*=\s*([^\n]+)", model)
        if not found:
            continue
        expression = found.group(1).strip()
        numbers = [int(n.replace("_", "")) for n in re.findall(r"[0-9][0-9_]*", expression)]
        product = 1
        for number in numbers:
            product *= number
        eq(f"kotlin {name} matches the spec", product, value)

    detector = open(detector_path, encoding="utf-8").read()
    kotlin_patterns = {
        literal.replace("\\\\", "\\") for literal in re.findall(r'Regex\("((?:[^"\\]|\\.)*)"\)', detector)
    }
    python_patterns = set(CORRECTION_ANY + CORRECTION_PREFIX + PRAISE_ANY + DIRECTIVE_ANY +
                          [pattern for _rule, pattern in SAFETY_RULES])
    missing = {p for p in python_patterns if p not in kotlin_patterns}
    extra = {p for p in kotlin_patterns if p not in python_patterns}
    check("every spec pattern exists in the Kotlin detector", not missing, f"missing: {sorted(missing)}")
    check("every Kotlin detector pattern is mirrored in the spec", not extra, f"extra: {sorted(extra)}")

    synth = open(os.path.join(SYNTH_SRC, "SkillSynth.kt"), encoding="utf-8").read()
    check("the synthesizer declares no network or file I/O", not re.search(
        r"\b(java\.net|URL|Socket|File\(|openConnection|Context)\b", synth))


def main() -> int:
    case_text()
    case_ids()
    case_detector()
    case_safety()
    case_learning()
    case_limits()
    case_prompt()
    case_control()
    case_codec()
    check_drift()

    for line in failures:
        print(f"FAIL  {line}")
    print()
    if failures:
        print(f"FAILED · {passed} passed, {len(failures)} failed")
        return 1
    print(f"PASS · {passed} checks (skill synthesizer spec)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
