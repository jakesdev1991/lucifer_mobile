package com.lucifer.mobile.cognition

/**
 * Where model prose is allowed to go, for the sinks that are *not* the chat answer.
 *
 * [OutputGate] closed the route every answer already took. It did not close the others, because they
 * are not answers: a router's clarifying question, a confirmation dialog's one-line explanation, a
 * workflow's plan summary, a diagnosis' root cause. Each was model text that reached a person, and each
 * was argued to be "just a structure" — which is true of how it is *used* and false of how it is
 * *read*. Parsing a sentence into a field does not stop it from being a sentence.
 *
 * Every function here has the same shape, and the shape is the policy:
 *
 *  - the text passes [OutputGate] before anybody sees it;
 *  - a blocked draft is **replaced**, never quoted — a caller that cannot show the prose still has to
 *    show *something*, and that something is written here, by us, in words the model never touched;
 *  - whatever is shown carries the gate's caveats when it has any.
 *
 * The fallbacks are deliberately boring. "I need a bit more to go on" is a worse question than the
 * model's, and that is the acceptable price: a withheld question costs one round trip, and a shown
 * falsehood costs the user's trust in every later answer.
 *
 * What this is not: a fact check, and not a substitute for the gate. It is the same gate, applied at
 * the sinks that were skipping it.
 */
object OutputBoundary {

    /** Shown instead of a clarification the gate refused. Ours, not the model's, so it can be quoted. */
    const val NEUTRAL_CLARIFICATION =
        "I need a bit more to go on before I act on that. Tell me what you want done, " +
            "or name the target: LOCAL, TERMUX, VM, or SHIZUKU."

    /** Shown in the confirmation dialog when the model's explanation cannot be shown. */
    const val NEUTRAL_EXPLANATION =
        "I understood this as a terminal request. Review the exact command below before it runs."

    /** Shown when a model-written plan summary is withheld. */
    const val NEUTRAL_PLAN_SUMMARY =
        "A plan was proposed from the blueprint. Each step is listed below with its own acceptance check."

    /**
     * A clarifying question the router wants to ask.
     *
     * Nothing was consulted to produce it — routing reads no device state — so the evidence is [Evidence.NONE]
     * and the gate is told so rather than being left to guess.
     */
    fun clarification(draft: String, goal: String): String =
        shown(
            draft = draft,
            goal = goal,
            fallback = NEUTRAL_CLARIFICATION,
            // A question is exempt from the stub check: "Which target?" is a complete clarification and
            // a terrible answer. Judging it by [AnswerAudit.MIN_ANSWER_CHARS] would block every good one.
            allowShort = true
        )

    /**
     * The one-line "why I understood it this way" shown beside a command awaiting confirmation.
     *
     * A blocked or empty explanation **never blocks the action**: the confirmation dialog is the safety
     * control, and it must not become capturable by a model that writes a bad sentence. The action is
     * still offered for approval, with a plain description that is ours.
     */
    fun actionExplanation(draft: String, command: String, target: String): String =
        shown(
            draft = draft,
            goal = "the user asked for a terminal action on $target: $command",
            fallback = NEUTRAL_EXPLANATION,
            allowShort = true
        )

    /** A workflow plan summary, before the steps are listed under it. */
    fun planSummary(draft: String, goal: String): String =
        shown(draft = draft, goal = goal, fallback = NEUTRAL_PLAN_SUMMARY, allowShort = true)

    /**
     * A diagnosis the resilience engine wants to show: a root cause, and the moves proposed from it.
     *
     * Unlike the others this one *has* evidence — the observations the recovery loop collected — so it is
     * judged against them. A diagnosis that shares no wording with what was observed is exactly the case
     * [AnswerAudit]'s `UNGROUNDED` warning exists for.
     *
     * @return the text to show, or null when the caller should fall back to its deterministic path. The
     *         deterministic fallback is the better answer here, not a consolation: it is derived from the
     *         observations, so it cannot be ungrounded by construction.
     */
    fun diagnosis(draft: String, goal: String, observations: List<String>): String? {
        val verdict = OutputGate.gate(
            goal = goal,
            draft = draft,
            evidence = OutputGate.Evidence(observations = observations)
        )
        if (verdict.blocked) return null
        // A *diagnosis* is the one sink where being ungrounded is not merely worth a caveat: the caller
        // has a deterministic alternative built from the observations themselves, so showing the model's
        // story beside it would be strictly worse than not showing it. Annotating here would produce
        // "here is my theory (which shares nothing with the evidence)" when the engine can simply say
        // what the evidence says.
        if (verdict.audit.findings.any { it.code == "UNGROUNDED" || it.code == "UNSOURCED" }) return null
        return verdict.text
    }

    /**
     * The shared body of every non-answer sink.
     *
     * @param allowShort a one-line sink (a question, an explanation) must not be judged by the length rule
     *        that exists to catch a model replying "ok" to a real question.
     */
    private fun shown(draft: String, goal: String, fallback: String, allowShort: Boolean): String {
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) return fallback
        val verdict = OutputGate.gate(goal = goal, draft = trimmed, evidence = OutputGate.Evidence.NONE)
        if (verdict.blocked) {
            return if (allowShort && onlyThin(verdict)) trimmed else fallback
        }
        return verdict.text ?: fallback
    }

    /**
     * True when the sole objection was that the text was too short to be an answer. A question or an
     * explanation is not an answer, so that objection does not apply — but nothing else is forgiven here.
     */
    private fun onlyThin(verdict: OutputGate.Verdict): Boolean {
        val findings = verdict.audit.findings
        return findings.isNotEmpty() && findings.all { it.code == "THIN" }
    }
}
