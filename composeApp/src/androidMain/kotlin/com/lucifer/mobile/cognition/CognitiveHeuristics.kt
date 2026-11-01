package com.lucifer.mobile.cognition

/**
 * Honest cognitive-state *heuristics* for a deep run — uncertainty, contradiction and complexity
 * signals computed from the trace and the audit, never from a model's self-report.
 *
 * Naming is load-bearing: these are **heuristics**, not measurements of a "cognitive state vector",
 * and nothing here detects a "phase transition". The archived RCOD material treated deviation in an
 * unproduced state vector as a physical signal; this app produces no such vector, so these scores
 * exist only to be recorded beside the run in [CognitionLedger] so the owner can see when a run
 * struggled. They are clamped to [0, 1], deterministic, and pure: no Android, no I/O, no network.
 */
object CognitiveHeuristics {

    /** Everything is clamped into this range; a heuristic that can exceed it is not comparable across runs. */
    fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

    /**
     * Uncertainty heuristic: how much the run's own audit leaned on caveats rather than evidence.
     *
     * - No answer at all → 1.0 (maximum uncertainty).
     * - BLOCKER findings → high; WARNING findings scale with count; NONE with no observations → 1.0
     *   (the model's recollection, not evidence).
     * - Otherwise the confidence ladder maps down: HIGH → 0.1, MEDIUM → 0.4, LOW → 0.7.
     */
    fun uncertainty(outcome: CognitionOutcome): Double {
        val audit = outcome.audit
        if (outcome.answer.isBlank()) return 1.0
        val blockers = audit.findings.count { it.severity == AnswerAudit.Severity.BLOCKER }
        if (blockers > 0) return clamp01(0.8 + 0.1 * blockers)
        val warnings = audit.warnings.size
        val base = when (audit.confidence) {
            AnswerAudit.Confidence.NONE -> 1.0
            AnswerAudit.Confidence.LOW -> 0.7
            AnswerAudit.Confidence.MEDIUM -> 0.4
            AnswerAudit.Confidence.HIGH -> 0.1
        }
        return clamp01(base + 0.05 * warnings)
    }

    /**
     * Contradiction heuristic: audit findings that flag the answer as disconnected from, or
     * overclaiming beyond, what was actually observed. These are the run's own internal tensions,
     * counted — not "contradictions exposed" as a reward signal.
     */
    fun contradiction(outcome: CognitionOutcome): Double {
        val codes = outcome.audit.findings.map { it.code }.toSet()
        val tension = codes.count {
            it in setOf("UNGROUNDED", "OVERCLAIM", "CITES_WITHOUT_SOURCE", "UNVERIFIED_DEVICE_CLAIM", "ECHO")
        }
        val failedCalls = outcome.trace.count { it.result != null && !it.result.ok }
        return clamp01(0.15 * tension + 0.1 * failedCalls)
    }

    /**
     * Complexity heuristic: how much work the run actually took, normalised against the budget —
     * not a measure of the *question's* difficulty, only of what this run spent.
     */
    fun complexity(outcome: CognitionOutcome, budget: LoopBudget = LoopBudget()): Double {
        val stepPart = outcome.steps.toDouble() / budget.maxSteps.coerceAtLeast(1)
        val timePart = outcome.elapsedMs.toDouble() / budget.maxElapsedMs.coerceAtLeast(1L)
        val callPart = outcome.toolCalls.toDouble() / budget.maxSteps.coerceAtLeast(1)
        return clamp01(0.5 * stepPart + 0.25 * timePart + 0.25 * callPart)
    }

    /** All three, labelled as heuristics, for the ledger line. */
    fun of(outcome: CognitionOutcome, budget: LoopBudget = LoopBudget()): Map<String, Double> = linkedMapOf(
        "uncertainty" to uncertainty(outcome),
        "contradiction" to contradiction(outcome),
        "complexity" to complexity(outcome, budget)
    )
}
