package com.lucifer.mobile.synth

import kotlin.math.pow

/**
 * The synthesizer.
 *
 * Every turn feeds it: the question, the answer, and — when the user reacts — a verdict on the answer.
 * From that it maintains a small, bounded set of sentences about how to behave, ranked by evidence, and
 * renders the best few into the prompt. Nothing here calls a model, touches the network, or writes a
 * file: it is a pure fold from ([SynthState], event) to [SynthState], which is why the rules can be
 * tested on a host and mirrored in Python.
 *
 * Three properties are deliberate:
 *
 *  - **Nothing is learned from one ambiguous signal.** "no internet is broken" is not a correction.
 *    [FeedbackDetector] would rather miss a complaint than invent one.
 *  - **Learning is reversible.** A skill that was injected and then corrected three times retires
 *    itself. Nothing accumulates forever; the state is capped at [SynthLimits.MAX_SKILLS].
 *  - **Learning cannot grant power.** [SkillSafety] blocks anything that would loosen a confirmation,
 *    the network switch, privilege or honesty, and records the refusal where the user can see it.
 */
object SkillSynth {

    /** What one incoming message did: the new state, what it taught him, and what it scored. */
    data class Outcome(
        val state: SynthState,
        val feedback: Feedback,
        val learned: List<SynthesizedSkill> = emptyList(),
        val scoredSkills: Int = 0
    )

    /** The rendered prompt fragment plus the ids it contains, so the next verdict can score them. */
    data class PromptBlock(val text: String, val skillIds: List<String> = emptyList()) {
        val isEmpty: Boolean get() = text.isBlank()
        companion object { val EMPTY = PromptBlock("") }
    }

    // ── recording ─────────────────────────────────────────────────────

    /**
     * Remember the answer that was just given, so the next user message can be judged against it.
     * [skillIds] are the skills that were in the prompt for this turn; they are what a correction
     * or a compliment scores.
     */
    fun recordAnswer(
        state: SynthState,
        question: String,
        answer: String,
        surface: String,
        now: Long,
        skillIds: List<String> = emptyList()
    ): SynthState {
        val asked = Text.clamp(question, SynthLimits.MAX_QUESTION_CHARS)
        if (asked.isBlank()) return state
        val pending = PendingTurn(
            question = asked,
            answer = Text.clamp(answer, SynthLimits.MAX_ANSWER_CHARS),
            surface = surface,
            at = now,
            skillIds = skillIds.distinct().take(8)
        )
        // ok=true here means "an answer was produced", not "it was right"; the verdict decides that.
        val experience = Experience(now, ExperienceKind.CHAT, surface, ok = true, question = asked)
        return state.copy(
            pending = pending,
            experiences = (state.experiences + experience).takeLast(SynthLimits.EXPERIENCE_RING),
            updatedAt = now
        )
    }

    /**
     * Judge a new user message against the pending turn, learn from it, and close the turn.
     *
     * The pending turn is cleared either way: if this message was not a reaction to it, the next
     * correction belongs to whatever comes next, and attaching it to a stale turn is how a well-meaning
     * learner ends up with a wrong lesson.
     */
    fun observeIncoming(state: SynthState, rawText: String, surface: String, now: Long): Outcome {
        val text = rawText.trim()
        if (text.isEmpty()) return Outcome(state, Feedback.NONE)

        val feedback = FeedbackDetector.detect(text)
        val pending = state.pending?.takeIf { now - it.at in 0..SynthLimits.PENDING_TTL_MS }
        if (pending == null) {
            val draft = if (feedback == Feedback.DIRECTIVE) learnDirective(state, text, surface, now) else null
            // Return the stored record, not the draft: consolidate() is what promotes it, and a caller
            // that showed the draft would always see CANDIDATE.
            val stored = draft?.let { updated(state, it, now) }.orEmpty()
            return Outcome(clearPending(state, now), feedback, stored, 0)
        }

        var next = state
        var scored = 0
        var learned: SynthesizedSkill? = null

        when (feedback) {
            Feedback.CORRECTION -> {
                next = score(next, pending.skillIds, win = false, now)
                scored = pending.skillIds.size
                learned = learnCorrection(next, pending, text, now)
            }
            Feedback.PRAISE -> {
                next = score(next, pending.skillIds, win = true, now)
                scored = pending.skillIds.size
            }
            Feedback.DIRECTIVE -> {
                learned = learnDirective(next, text, surface, now)
            }
            Feedback.NONE -> {
                // The same question again, minutes later, is the quietest failure signal there is:
                // he answered, and the answer did not land.
                if (Text.sameQuestion(pending.question, text) && now - pending.at <= SynthLimits.REPEAT_WINDOW_MS) {
                    next = score(next, pending.skillIds, win = false, now)
                    scored = pending.skillIds.size
                    learned = learnRouting(next, pending.question, text, now)
                }
            }
        }

        next = learned?.let { upsert(next, it, now) } ?: next
        next = clearPending(next, now)
        return Outcome(
            state = consolidate(next, now),
            feedback = feedback,
            learned = learned?.let { listOfNotNull(next.byId(it.id)) }.orEmpty(),
            scoredSkills = scored
        )
    }

    /** Upsert a draft and read it back, so callers see the promoted record rather than the draft. */
    private fun updated(state: SynthState, draft: SynthesizedSkill, now: Long): List<SynthesizedSkill> {
        val next = upsert(state, draft, now)
        return listOfNotNull(next.byId(draft.id))
    }

    /**
     * A command that failed and a tactic that fixed it. The synthesizer does not choose tactics — the
     * recovery loop does — it only remembers which one was chosen and how it went.
     */
    fun learnProcedure(
        state: SynthState,
        failureClass: String,
        target: String,
        tactic: String,
        worked: Boolean,
        now: Long
    ): SynthState {
        val failKey = Text.signature(failureClass.ifBlank { "unknown" }, maxTokens = 3)
        val targetKey = Text.signature(target.ifBlank { "unknown" }, maxTokens = 2)
        val trigger = "when $failKey fails on $targetKey"
        val draft = SynthesizedSkill(
            id = SkillIds.of(SkillKind.PROCEDURE, trigger),
            kind = SkillKind.PROCEDURE,
            trigger = Text.clamp(trigger, SynthLimits.MAX_TRIGGER_CHARS),
            instruction = Text.clamp(
                "When a ${failKey.ifBlank { "command" }} failure happens on ${targetKey.ifBlank { "a target" }}, try $tactic first.",
                SynthLimits.MAX_INSTRUCTION_CHARS
            ),
            evidence = listOf(Text.clamp("$failureClass → $tactic", 120)),
            wins = if (worked) 1 else 0,
            losses = if (worked) 0 else 1,
            createdAt = now,
            updatedAt = now,
            status = SkillStatus.CANDIDATE
        )
        var next = upsert(state, draft, now)
        next = next.copy(
            experiences = (next.experiences + Experience(now, ExperienceKind.COMMAND, "ANY", worked, failureClass.take(80), tactic.take(80)))
                .takeLast(SynthLimits.EXPERIENCE_RING)
        )
        return consolidate(next, now)
    }

    // ── learning ──────────────────────────────────────────────────────

    private fun learnCorrection(state: SynthState, pending: PendingTurn, correction: String, now: Long): SynthesizedSkill {
        val trigger = Text.signature(pending.question)
        val verdict = SkillSafety.review(correction)
        val instruction = Text.clamp(
            "When asked \"${Text.clamp(pending.question, 90)}\", the earlier answer was wrong. The user's correction: ${Text.clamp(correction, 110)}",
            SynthLimits.MAX_INSTRUCTION_CHARS
        )
        return synthesize(state, SkillKind.CORRECTION, trigger, instruction, correction, pending.surface, verdict, now)
    }

    private fun learnDirective(state: SynthState, text: String, surface: String, now: Long): SynthesizedSkill? {
        if (text.length > FeedbackDetector.MAX_DIRECTIVE_CHARS) return null
        val verdict = SkillSafety.review(text)
        val instruction = Text.clamp("User preference: ${Text.clamp(text, 160)}", SynthLimits.MAX_INSTRUCTION_CHARS)
        return synthesize(state, SkillKind.DIRECTIVE, Text.signature(text), instruction, text, surface, verdict, now)
    }

    private fun learnRouting(state: SynthState, question: String, again: String, now: Long): SynthesizedSkill {
        val trigger = Text.signature(question)
        val instruction = Text.clamp(
            "Asked \"${Text.clamp(trigger, 80)}\" a second time: lead with the direct answer first, then the detail. The first answer did not land.",
            SynthLimits.MAX_INSTRUCTION_CHARS
        )
        return synthesize(state, SkillKind.ROUTING, trigger, instruction, again, ANY_SURFACE, SkillSafety.Verdict(true), now)
    }

    private fun synthesize(
        state: SynthState,
        kind: SkillKind,
        trigger: String,
        instruction: String,
        evidence: String,
        surface: String,
        verdict: SkillSafety.Verdict,
        now: Long
    ): SynthesizedSkill = SynthesizedSkill(
        id = SkillIds.of(kind, trigger),
        kind = kind,
        trigger = Text.clamp(trigger.ifBlank { instruction }, SynthLimits.MAX_TRIGGER_CHARS),
        instruction = instruction,
        evidence = listOf(Text.clamp(evidence, 120)),
        createdAt = now,
        updatedAt = now,
        status = if (verdict.allowed) SkillStatus.CANDIDATE else SkillStatus.BLOCKED,
        surface = surface.ifBlank { ANY_SURFACE },
        note = if (verdict.allowed) "" else verdict.rule
    )

    /**
     * Merge a draft into the set.
     *
     * Same id means same lesson: evidence accumulates (capped), counters are kept, and the record is
     * re-evaluated. A directive that overlaps an older one on two or more content words supersedes it —
     * otherwise "keep it short" would compete with a later "give me the full detail" forever.
     */
    private fun upsert(state: SynthState, draft: SynthesizedSkill, now: Long): SynthState {
        val blocked = draft.status == SkillStatus.BLOCKED
        var skills = state.skills
        val existing = skills.firstOrNull { it.id == draft.id }
        val merged: SynthesizedSkill = if (existing == null) {
            draft
        } else {
            existing.copy(
                evidence = (existing.evidence + draft.evidence).distinct().take(SynthLimits.MAX_EVIDENCE),
                wins = existing.wins + draft.wins,
                losses = existing.losses + draft.losses,
                instruction = draft.instruction,
                updatedAt = now,
                status = if (existing.status == SkillStatus.BLOCKED) SkillStatus.BLOCKED else existing.status,
                note = draft.note.ifBlank { existing.note }
            )
        }

        if (draft.kind == SkillKind.DIRECTIVE && !blocked) {
            skills = supersede(skills, merged, now)
        }
        skills = if (existing == null) skills + merged else skills.map { if (it.id == merged.id) merged else it }

        val next = state.copy(
            skills = skills,
            refused = state.refused + if (blocked) 1 else 0,
            updatedAt = now
        )
        return consolidate(next, now)
    }

    private fun supersede(skills: List<SynthesizedSkill>, newer: SynthesizedSkill, now: Long): List<SynthesizedSkill> {
        val newerTokens = Text.tokens(newer.instruction)
        if (newerTokens.isEmpty()) return skills
        return skills.map { candidate ->
            if (candidate.kind != SkillKind.DIRECTIVE || candidate.id == newer.id) {
                candidate
            } else if (candidate.status == SkillStatus.ACTIVE || candidate.status == SkillStatus.CANDIDATE) {
                val overlap = Text.tokens(candidate.instruction).count { it in newerTokens }
                if (overlap >= 2) {
                    candidate.copy(
                        status = SkillStatus.RETIRED,
                        note = "superseded by a newer preference",
                        updatedAt = now
                    )
                } else candidate
            } else candidate
        }
    }

    // ── scoring and lifecycle ─────────────────────────────────────────

    private fun score(state: SynthState, ids: List<String>, win: Boolean, now: Long): SynthState {
        if (ids.isEmpty()) return state
        val wanted = ids.toSet()
        return state.copy(
            skills = state.skills.map { skill ->
                if (skill.id in wanted && skill.status != SkillStatus.BLOCKED) {
                    val scored = if (win) {
                        skill.copy(wins = skill.wins + 1, hits = skill.hits + 1, updatedAt = now)
                    } else {
                        skill.copy(losses = skill.losses + 1, hits = skill.hits + 1, updatedAt = now)
                    }
                    refresh(scored)
                } else skill
            },
            updatedAt = now
        )
    }

    /** Count a skill as exposed when it went into a prompt. Exposure alone never promotes it. */
    fun markExposed(state: SynthState, ids: List<String>, now: Long): SynthState {
        if (ids.isEmpty()) return state
        val wanted = ids.toSet()
        return state.copy(
            skills = state.skills.map { if (it.id in wanted) it.copy(hits = it.hits + 1) else it },
            updatedAt = now
        )
    }

    /**
     * Promotion and retirement.
     *
     * Corroboration is evidence plus wins, plus a head start per kind: a preference the user stated and
     * a correction the user dictated are both authoritative statements, so they go live immediately; a
     * repeated question and a working tactic have to prove themselves twice. Retirement needs repeated
     * losses *and* a confidence that has actually fallen — one bad turn does not delete a good habit.
     */
    private fun refresh(skill: SynthesizedSkill): SynthesizedSkill {
        if (skill.status == SkillStatus.BLOCKED) return skill
        val headStart = when (skill.kind) {
            SkillKind.DIRECTIVE -> 2
            SkillKind.CORRECTION -> 1
            SkillKind.PROCEDURE, SkillKind.ROUTING -> 0
        }
        val corroboration = skill.evidence.size + skill.wins + headStart
        return when {
            skill.status == SkillStatus.ACTIVE &&
                skill.losses >= SynthLimits.RETIRE_LOSSES &&
                skill.confidence < SynthLimits.RETIRE_BELOW ->
                skill.copy(status = SkillStatus.RETIRED, note = "corrected too often to keep")

            skill.status == SkillStatus.RETIRED &&
                skill.wins >= 2 && skill.confidence >= 0.6 ->
                skill.copy(status = SkillStatus.ACTIVE, note = "")

            skill.status == SkillStatus.CANDIDATE && corroboration >= SynthLimits.PROMOTE_AT ->
                skill.copy(status = SkillStatus.ACTIVE, note = "")

            else -> skill
        }
    }

    // ── consolidation ────────────────────────────────────────────────

    /**
     * Runs after every observation and on load: refresh every status, drop a candidate that was never
     * used and is older than two weeks, merge duplicate ids, and enforce the cap by keeping the skills
     * that are actually doing work.
     */
    fun consolidate(state: SynthState, now: Long): SynthState {
        val deduped = LinkedHashMap<String, SynthesizedSkill>()
        for (skill in state.skills) {
            val prior = deduped[skill.id]
            deduped[skill.id] = if (prior == null) skill else {
                if (prior.wins + prior.evidence.size >= skill.wins + skill.evidence.size) {
                    prior.copy(
                        wins = maxOf(prior.wins, skill.wins),
                        losses = maxOf(prior.losses, skill.losses),
                        evidence = (prior.evidence + skill.evidence).distinct().take(SynthLimits.MAX_EVIDENCE)
                    )
                } else skill
            }
        }

        val refreshed = deduped.values.map { skill ->
            val live = refresh(skill)
            if (live.status == SkillStatus.CANDIDATE &&
                live.hits == 0 &&
                live.wins == 0 &&
                now - live.createdAt > STALE_CANDIDATE_MS
            ) {
                live.copy(status = SkillStatus.RETIRED, note = "never used")
            } else live
        }

        // Live skills outrank candidates, retired history and refusals, so a full set of blocked
        // records can never crowd out something that is actually working.
        val ranked = refreshed.sortedWith(
            compareByDescending<SynthesizedSkill> { priority(it) }
                .thenByDescending { it.wins }
                .thenByDescending { it.updatedAt }
        )

        return state.copy(
            skills = ranked.take(SynthLimits.MAX_SKILLS),
            experiences = state.experiences.takeLast(SynthLimits.EXPERIENCE_RING),
            updatedAt = now
        )
    }

    private fun priority(skill: SynthesizedSkill): Int = when (skill.status) {
        SkillStatus.ACTIVE -> 3
        SkillStatus.CANDIDATE -> 2
        SkillStatus.RETIRED -> 1
        SkillStatus.BLOCKED -> 0
    }

    private const val STALE_CANDIDATE_MS = 14L * 24L * 60L * 60L * 1_000L

    // ── rendering ────────────────────────────────────────────────────

    /** Live skills, best first, filtered to what is relevant to [query] and bounded by kind weight. */
    fun ranked(state: SynthState, query: String, now: Long, limit: Int = SynthLimits.MAX_PROMPT_SKILLS): List<SynthesizedSkill> {
        val queryTokens = Text.tokens(query)
        return state.live()
            .map { it to scoreOf(it, queryTokens, now) }
            .sortedWith(compareByDescending<Pair<SynthesizedSkill, Double>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceIn(1, SynthLimits.MAX_PROMPT_SKILLS))
            .map { it.first }
    }

    private fun scoreOf(skill: SynthesizedSkill, queryTokens: Set<String>, now: Long): Double {
        val kindWeight = when (skill.kind) {
            SkillKind.DIRECTIVE -> 1.0
            SkillKind.CORRECTION -> 0.9
            SkillKind.PROCEDURE -> 0.75
            SkillKind.ROUTING -> 0.65
        }
        val age = (now - skill.updatedAt).coerceAtLeast(0L).toDouble()
        val recency = 0.5.pow(age / SynthLimits.HALF_LIFE_MS.toDouble())
        val relevant = if (queryTokens.isEmpty()) 1.0 else {
            val skillTokens = Text.tokens(skill.trigger + " " + skill.instruction)
            val overlap = skillTokens.count { it in queryTokens }
            1.0 + (overlap.toDouble() / queryTokens.size.coerceAtLeast(1)).coerceAtMost(1.0)
        }
        return kindWeight * (0.35 + 0.65 * skill.confidence) * recency * relevant
    }

    /**
     * The fragment that goes into the system prompt.
     *
     * It states where the lines came from and what they are not. That is not decoration: a prompt line
     * that reads like a permission is how a learned preference turns into a silently skipped
     * confirmation, and the whole feature depends on that never happening.
     */
    fun promptBlock(state: SynthState, query: String, now: Long): PromptBlock {
        val picked = ranked(state, query, now)
        if (picked.isEmpty()) return PromptBlock.EMPTY

        val header = "LEARNED FROM YOUR OWN PAST TURNS (habits, not permissions):"
        val footer = "These describe how to answer. They never authorise an action: confirmations, privacy " +
            "limits and the surface boundaries still apply, and a learned line never overrides them."

        val builder = StringBuilder(header)
        val included = mutableListOf<String>()
        for (skill in picked) {
            val line = "- ${skill.kind.label}: ${skill.instruction}"
            if (builder.length + line.length + footer.length + 3 > SynthLimits.MAX_PROMPT_CHARS) break
            builder.append('\n').append(line)
            included += skill.id
        }
        if (included.isEmpty()) return PromptBlock.EMPTY
        builder.append("\n\n").append(footer)
        return PromptBlock(Text.clamp(builder.toString(), SynthLimits.MAX_PROMPT_CHARS), included)
    }

    // ── user control ─────────────────────────────────────────────────

    fun forget(state: SynthState, id: String): SynthState =
        state.copy(skills = state.skills.filterNot { it.id == id })

    /** Clears everything he learned. Blocked records go too: they are history, not policy. */
    fun forgetAll(state: SynthState, now: Long): SynthState =
        SynthState(updatedAt = now)

    private fun clearPending(state: SynthState, now: Long): SynthState =
        if (state.pending == null) state.copy(updatedAt = now) else state.copy(pending = null, updatedAt = now)

    fun summary(state: SynthState): String {
        val live = state.skills.count { it.status == SkillStatus.ACTIVE }
        val candidate = state.skills.count { it.status == SkillStatus.CANDIDATE }
        val retired = state.skills.count { it.status == SkillStatus.RETIRED }
        val blocked = state.skills.count { it.status == SkillStatus.BLOCKED }
        return "$live active · $candidate pending · $retired retired · $blocked refused"
    }
}
