package com.lucifer.mobile.cognition

/**
 * The gate every reply passes through before a person reads it.
 *
 * [AnswerAudit] already knows how to *judge* a draft. Nothing enforced that judgment on the path most
 * answers actually take: the single-pass council in `ToEReasoningEngine.reason` returned the model's
 * paragraph straight to the transcript, and the deterministic surface answers never met a check at all.
 * The deep loop was the only route that behaved, because it called the audit inline — which is exactly
 * the shape of bug this file exists to remove: a check that each caller has to remember to make is not a
 * check, it is a convention, and conventions are skipped by the next caller.
 *
 * So this is not another verification you can opt into. `reason(...)` ends here for every route, and
 * there are three outcomes, all of them stated:
 *
 *  - `PASS` — the draft raised no objection. Shown as it stands, untouched.
 *  - `ANNOTATED` / `REPAIRED` — shown, but the caveats ride along in the text. A warning that nobody can
 *    see is the same as no warning: the reader has to know which sentence was not checked.
 *  - `BLOCKED` — **withheld.** [Verdict.text] is null. The draft never reaches the transcript; the caller
 *    renders [refusal] instead, which names the objection without reproducing the prose.
 *
 * The invariant `text == null ⟺ blocked` is asserted in [Verdict]'s constructor rather than described in
 * a comment, so a future edit that returns blocked text has to delete an assertion to do it.
 *
 * What this does *not* do: decide truth. It cannot. It decides whether a draft is **shippable** — whether
 * it addresses the question, whether it was grounded in something the loop observed, and whether it can
 * claim confidence it has not earned. A clean pass means "no objection found by a rule", not "correct".
 */
object OutputGate {

    /** How many audits a draft may consume. Two: the original, and one repair, as the loop already does. */
    const val MAX_ATTEMPTS = 2

    enum class Decision {
        /** Shown unchanged. */
        PASS,

        /** Shown with its caveats appended, because the warnings are real but not disqualifying. */
        ANNOTATED,

        /** A blocked draft was repaired and the repair passed. */
        REPAIRED,

        /** Withheld. */
        BLOCKED
    }

    /** What the draft was supposed to be grounded in. Empty evidence is allowed; it lowers confidence. */
    data class Evidence(
        val observations: List<String> = emptyList(),
        val toolEffects: List<SideEffect> = emptyList(),
        val toolIds: List<String> = emptyList()
    ) {
        companion object {
            /** Nothing was consulted — the model's own recollection. Legal, and labelled as such. */
            val NONE = Evidence()
        }
    }

    /**
     * @param text what may be shown; **null if and only if** [blocked].
     * @param audit the last audit result, including the warnings that could not be cleared.
     * @param attempts how many audits this draft consumed (1 = passed or blocked first time).
     * @param note a short, truthful account of what happened, for the trace and the panel.
     */
    data class Verdict(
        val decision: Decision,
        val text: String?,
        val audit: AnswerAudit.Result,
        val attempts: Int,
        val note: String
    ) {
        init {
            require((text == null) == (decision == Decision.BLOCKED)) {
                "a blocked verdict must carry no text, and a shown verdict must carry text"
            }
        }

        val blocked: Boolean get() = decision == Decision.BLOCKED
    }

    /** A repair the caller can perform. Returns a new draft, or null when it cannot do better. */
    fun interface Repair {
        fun repair(draft: String, findings: List<AnswerAudit.Finding>): String?
    }

    /**
     * Gate a draft produced by a model.
     *
     * @param goal what the user asked, so an answer that restates the question can be recognised.
     * @param draft what the model wrote.
     * @param evidence what was actually observed this turn.
     * @param repair one bounded attempt, or null when the caller has nothing better to offer.
     */
    fun gate(
        goal: String,
        draft: String,
        evidence: Evidence = Evidence.NONE,
        repair: Repair? = null
    ): Verdict {
        var attempts = 0
        var current = draft.trim()
        var result = audit(goal, current, evidence)
        attempts++

        while (result.blocked && repair != null && attempts < MAX_ATTEMPTS) {
            val fixed = try {
                repair.repair(current, result.findings)?.trim()
            } catch (_: Exception) {
                // A repair that throws is a repair that did not happen. The draft stays blocked.
                null
            }
            if (fixed.isNullOrBlank() || fixed == current) break
            current = fixed
            result = audit(goal, current, evidence)
            attempts++
        }

        if (result.blocked) {
            val blockers = result.findings.filter { it.severity == AnswerAudit.Severity.BLOCKER }
            return Verdict(
                decision = Decision.BLOCKED,
                text = null,
                audit = result,
                attempts = attempts,
                note = "withheld after $attempts check(s): " +
                    blockers.joinToString("; ") { it.code.lowercase() }
            )
        }

        val warnings = result.warnings
        if (warnings.isEmpty()) {
            return Verdict(
                decision = if (attempts > 1) Decision.REPAIRED else Decision.PASS,
                text = current,
                audit = result,
                attempts = attempts,
                note = if (attempts > 1) "the draft failed review, was repaired, and passed" else "no objection"
            )
        }

        val disclosed = current + "\n\n" + disclosure(warnings)
        return Verdict(
            decision = if (attempts > 1) Decision.REPAIRED else Decision.ANNOTATED,
            text = disclosed,
            audit = result,
            attempts = attempts,
            note = "${warnings.size} caveat(s) carried into the reply: " +
                warnings.joinToString("; ") { it.code.lowercase() }
        )
    }

    /**
     * Text that is not model prose — a capability answer read out of [Surface]'s own records, or the
     * loop's account of why it stopped. It is grounded by construction, so auditing it as if a model had
     * guessed it would produce false blockers ("this device" with no device read, when the text came
     * *from* the surface registry).
     *
     * This is a bypass, so it is a narrow one: it takes no draft from a model, and the harness asserts
     * exactly which call sites use it.
     */
    fun deterministic(text: String): Verdict = Verdict(
        decision = Decision.PASS,
        text = text,
        audit = AnswerAudit.Result(
            findings = emptyList(),
            confidence = AnswerAudit.Confidence.MEDIUM,
            confidenceReason = "deterministic: read from the surface's own records, not generated"
        ),
        attempts = 0,
        note = "deterministic text"
    )

    /**
     * A reply the cognition loop already gated internally, carried to the boundary so the boundary can
     * still refuse it.
     *
     * The runner audits (and repairs twice) before returning, so re-auditing the finished text with no
     * evidence would flag a grounded answer as ungrounded — the loop's evidence does not survive as a
     * string list. Carrying the verdict is the honest option; honouring it here is defence in depth, not
     * decoration: if a future change lets a blocked draft out of the runner, this refuses it anyway.
     *
     * A carried verdict with warnings is `ANNOTATED`, not `PASS`. That distinction was missing and it
     * mattered: the runner's footer said "N caveat(s) below" and this returned the text without them, so
     * the reader was told to look for a disclosure that never arrived. An unfulfilled promise to disclose
     * is worse than silence, because it reads as diligence.
     */
    fun carried(text: String, audit: AnswerAudit.Result): Verdict {
        if (audit.blocked) {
            return Verdict(
                decision = Decision.BLOCKED,
                text = null,
                audit = audit,
                attempts = 1,
                note = "withheld at the boundary: the loop's own audit had blocked it"
            )
        }
        val warnings = audit.warnings
        if (warnings.isEmpty()) {
            return Verdict(
                decision = Decision.PASS,
                text = text,
                audit = audit,
                attempts = 1,
                note = "already reviewed in the loop"
            )
        }
        // The footer already points down at these; if the text ends with one, do not stack a second.
        val disclosed = if (text.contains("Gate: shown with")) text
        else text.trimEnd() + "\n\n" + disclosure(warnings)
        return Verdict(
            decision = Decision.ANNOTATED,
            text = disclosed,
            audit = audit,
            attempts = 1,
            note = "carried with ${warnings.size} caveat(s) attached: " +
                warnings.joinToString("; ") { it.code.lowercase() }
        )
    }

    /**
     * The caveat block appended to an annotated reply. It names the codes *and* the first sentence of
     * objection, because "caveat(s)" alone tells a reader nothing they can act on.
     */
    fun disclosure(warnings: List<AnswerAudit.Finding>): String {
        val codes = warnings.joinToString(", ") { it.code.lowercase() }
        val first = warnings.firstOrNull()?.message.orEmpty()
        return "Gate: shown with ${warnings.size} caveat(s) — $codes.${
            if (first.isNotBlank()) " $first" else ""
        }"
    }

    /**
     * What the user sees instead of a blocked draft. It names the objection and says where the draft
     * went; it never reproduces the text, because reproducing it is the failure mode being prevented.
     */
    fun refusal(verdict: Verdict): String {
        val blockers = verdict.audit.findings.filter { it.severity == AnswerAudit.Severity.BLOCKER }
        return buildString {
            appendLine("I am not going to show you that reply.")
            appendLine()
            if (blockers.isNotEmpty()) {
                appendLine("Why:")
                blockers.forEach { appendLine("- " + it.message) }
                appendLine()
            }
            appendLine(
                "The draft was produced and withheld rather than shown; it is in the cognition trace with " +
                    "its findings, not in your transcript."
            )
            append("Ask again, or ask for /deep and I will go and check instead of guessing.")
        }
    }

    private fun audit(goal: String, draft: String, evidence: Evidence): AnswerAudit.Result =
        AnswerAudit.audit(
            goal = goal,
            answer = draft,
            observations = evidence.observations,
            toolEffects = evidence.toolEffects,
            toolIds = evidence.toolIds
        )
}
