package com.lucifer.mobile.resilience

/**
 * The recovery loop: classify -> investigate -> research -> plan -> act -> learn.
 *
 * This is the state machine and the ranking/planning logic, kept pure so it can be exercised
 * without a device. The Android layer supplies probes, a browser, a model, and confirmation.
 */
enum class RecoveryPhase {
    /** A failure was captured but nothing has been tried yet. */
    CLASSIFIED,
    INVESTIGATING,
    RESEARCHING,
    PLANNING,
    /** A plan exists; Lucifer is showing it and waiting for the go-ahead. */
    PLANNED,
    /** An attempt ran (or was approved) and its result is being recorded. */
    VERIFYING,
    RESOLVED,
    /** Out of tactics or out of attempts; the loop stops and hands control back. */
    EXHAUSTED
}

data class RecoveryAttempt(
    val kind: RecoveryKind,
    val note: String,
    val succeeded: Boolean? = null,
    val at: Long = System.currentTimeMillis()
)

data class RecoveryEpisode(
    val id: String,
    val signal: FailureSignal,
    val phase: RecoveryPhase = RecoveryPhase.CLASSIFIED,
    val plan: List<RecoveryMove> = emptyList(),
    val attempts: List<RecoveryAttempt> = emptyList(),
    val diagnosis: Diagnosis? = null,
    val research: ResearchBrief? = null,
    val notes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val attemptedKinds: Set<RecoveryKind> get() = attempts.map { it.kind }.toSet()
    val failedKinds: Set<RecoveryKind> get() = attempts.filter { it.succeeded == false }.map { it.kind }.toSet()
    val nextMove: RecoveryMove? get() = plan.firstOrNull { it.kind !in attemptedKinds }
    val isFinished: Boolean get() = phase == RecoveryPhase.RESOLVED || phase == RecoveryPhase.EXHAUSTED
    val summaryLine: String
        get() = "${signal.failureClass.label} on ${signal.target.displayName} · attempt ${attempts.size}/$MAX_ATTEMPTS · ${phase.name.lowercase()}"

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/** Ranks and merges tactics using both the playbook and real outcomes in the ledger. */
class RecoveryPlanner(private val ledger: LearningLedger) {

    fun startEpisode(
        signal: FailureSignal,
        id: String = "ep-" + ResilienceText.stableKey(listOf(signal.key, signal.detectedAt.toString())),
        now: Long = System.currentTimeMillis()
    ): RecoveryEpisode = RecoveryEpisode(id = id, signal = signal, createdAt = now, updatedAt = now)

    /**
     * Merge sources into one ordered plan:
     *   diagnosis moves, the learned best tactic, the playbook, then lateral fallbacks.
     * Anything already attempted and failed for this episode is excluded.
     */
    fun planMoves(
        episode: RecoveryEpisode,
        extraMoves: List<RecoveryMove> = emptyList(),
        limit: Int = 4,
        now: Long = System.currentTimeMillis()
    ): List<RecoveryMove> {
        val signal = episode.signal
        val diagnosisMoves = episode.diagnosis?.nextMoves.orEmpty()
        val learnedBest = ledger.bestMove(signal.key, now)
        val learnedMove = learnedBest?.let { ranked ->
            RecoveryPlaybook.candidates(signal).firstOrNull { it.kind == ranked.kind }
                ?: RecoveryPlaybook.lateralFallback(signal).firstOrNull { it.kind == ranked.kind }
        }

        val ordered = buildList {
            diagnosisMoves.forEach { add(it.copy(origin = MoveOrigin.DIAGNOSIS)) }
            if (learnedMove != null && learnedBest.stat.successes > 0) {
                add(learnedMove.copy(
                    origin = MoveOrigin.LEARNED,
                    rationale = "Learned locally: ${learnedBest.stat.successes}/${learnedBest.stat.attempts} successful here."
                ))
            }
            RecoveryPlaybook.candidates(signal).forEach { add(it) }
            extraMoves.forEach { add(it) }
            RecoveryPlaybook.lateralFallback(signal).forEach { add(it) }
        }

        val seen = mutableSetOf<RecoveryKind>()
        val merged = ordered.filter { move ->
            if (move.kind in episode.failedKinds) return@filter false
            seen.add(move.kind)
        }

        // Learned performance adjusts the playbook order without overriding a fresh diagnosis.
        val stats = ledger.statsFor(signal.key)
        val scores = ledger.rank(signal.key, merged.map { it.kind }, now).associate { it.kind to it.score }
        val scored = merged.map { move ->
            val stat = stats[move.kind] ?: StrategyStat(move.kind, 0, 0, 0L)
            val learnedScore = scores[move.kind] ?: 0.5
            val bias = when (move.safety) {
                MoveSafety.READ_ONLY -> 0.06
                MoveSafety.SUGGESTION -> 0.02
                MoveSafety.REQUIRES_CONFIRMATION -> -0.04
            }
            val diagnosisBias = if (move.origin == MoveOrigin.DIAGNOSIS) 0.10 else 0.0
            val triedBonus = if (stat.attempts > 0) stat.successRate * 0.20 - 0.10 else 0.0
            Triple(move, learnedScore + bias + diagnosisBias + triedBonus, merged.indexOf(move))
        }
        return scored.sortedWith(
            compareByDescending<Triple<RecoveryMove, Double, Int>> { it.second }.thenBy { it.third }
        ).map { it.first }.distinctBy { it.kind }.take(limit)
    }

    fun withPlan(episode: RecoveryEpisode, moves: List<RecoveryMove>, now: Long = System.currentTimeMillis()): RecoveryEpisode =
        if (moves.isEmpty()) {
            episode.copy(
                phase = RecoveryPhase.EXHAUSTED,
                notes = (episode.notes + "No untried tactic remains in the playbook.").takeLast(12),
                updatedAt = now
            )
        } else episode.copy(
            phase = RecoveryPhase.PLANNED,
            plan = moves,
            notes = (episode.notes + "Planned ${moves.size} move(s).").takeLast(12),
            updatedAt = now
        )

    /**
     * Record what happened. Every attempt becomes a lesson, which is exactly how the next
     * planning pass gets smarter.
     */
    fun recordAttempt(
        episode: RecoveryEpisode,
        move: RecoveryMove,
        succeeded: Boolean,
        note: String,
        now: Long = System.currentTimeMillis()
    ): RecoveryEpisode {
        val cleanNote = ResilienceRedactor.redact(note, LearningLedger.MAX_NOTE_CHARS)
        ledger.record(
            Lesson(
                signatureKey = episode.signal.key,
                failureClass = episode.signal.failureClass,
                kind = move.kind,
                target = episode.signal.target,
                tool = episode.signal.tool,
                succeeded = succeeded,
                note = cleanNote,
                createdAt = now,
                episodeId = episode.id
            )
        )
        val attempts = episode.attempts + RecoveryAttempt(move.kind, cleanNote, succeeded, now)
        val exhausted = !succeeded && attempts.size >= RecoveryEpisode.MAX_ATTEMPTS
        val phase = when {
            succeeded -> RecoveryPhase.RESOLVED
            exhausted -> RecoveryPhase.EXHAUSTED
            else -> RecoveryPhase.CLASSIFIED
        }
        val remaining = if (succeeded) emptyList() else episode.plan.filterNot { it.kind == move.kind }
        return episode.copy(
            attempts = attempts,
            phase = phase,
            plan = if (succeeded) episode.plan else remaining,
            notes = (episode.notes + (if (succeeded) "Resolved by ${move.kind.label}." else "Tried ${move.kind.label}: still failing.")).takeLast(12),
            updatedAt = now
        )
    }

    fun withDiagnosis(episode: RecoveryEpisode, diagnosis: Diagnosis, now: Long = System.currentTimeMillis()): RecoveryEpisode =
        episode.copy(
            diagnosis = diagnosis,
            phase = RecoveryPhase.PLANNING,
            notes = (episode.notes + "Diagnosis (${diagnosis.source.name.lowercase()}): ${diagnosis.rootCause.take(300)}").takeLast(12),
            updatedAt = now
        )

    fun withResearch(episode: RecoveryEpisode, brief: ResearchBrief, now: Long = System.currentTimeMillis()): RecoveryEpisode =
        episode.copy(
            research = brief,
            phase = RecoveryPhase.PLANNING,
            notes = (episode.notes + "Research: ${brief.sources.size} source(s) from ${brief.queries.size} query/queries.").takeLast(12),
            updatedAt = now
        )

    fun mark(episode: RecoveryEpisode, phase: RecoveryPhase, note: String? = null, now: Long = System.currentTimeMillis()): RecoveryEpisode =
        episode.copy(
            phase = phase,
            notes = if (note.isNullOrBlank()) episode.notes else (episode.notes + note).takeLast(12),
            updatedAt = now
        )

    /** Plain-language handoff that can be shown to the user or routed through the router. */
    fun describe(episode: RecoveryEpisode, now: Long = System.currentTimeMillis()): String = buildString {
        append("Failure: ").append(episode.signal.summary).append('\n')
        append("Signature: ").append(episode.signal.key).append(" · attempts ").append(episode.attempts.size)
            .append('/').append(RecoveryEpisode.MAX_ATTEMPTS).append('\n')
        val lesson = ledger.summary(episode.signal.key, now)
        append("Learned: ").append(lesson.text).append('\n')
        if (episode.diagnosis != null) append(episode.diagnosis.render()).append('\n')
        if (episode.plan.isNotEmpty()) {
            append("Plan:\n")
            episode.plan.forEachIndexed { index, move ->
                val marker = if (move.kind in episode.attemptedKinds) "x" else "${index + 1}"
                append("  [$marker] ").append(move.title).append(" (").append(move.kind.label).append(")")
                if (move.safety == MoveSafety.REQUIRES_CONFIRMATION) append(" · needs approval")
                append('\n')
            }
        }
        if (episode.notes.isNotEmpty()) append("Log: ").append(episode.notes.takeLast(4).joinToString(" | ")).append('\n')
    }.trim()
}

/** Asks the model for a fresh plan when the playbook and the ledger have not cracked it. */
class RecoveryCoach(
    private val thinker: RecoveryThinker?,
    private val planner: RecoveryPlanner
) {
    /**
     * Returns moves ordered for action. When no model is reachable this is the deterministic
     * playbook order, adjusted by what has actually worked before.
     */
    suspend fun plan(
        episode: RecoveryEpisode,
        evidence: EvidenceBundle? = null,
        research: ResearchBrief? = null,
        limit: Int = 4,
        now: Long = System.currentTimeMillis()
    ): List<RecoveryMove> {
        val base = planner.planMoves(episode, limit = limit, now = now)
        val model = runCatching { askModel(episode, evidence, research, limit) }.getOrNull()
        if (model.isNullOrEmpty()) return base
        // Model suggestions lead, but only tactics the app knows how to carry out survive.
        val combined = (model + base).distinctBy { it.kind }.take(limit)
        return combined
    }

    private suspend fun askModel(
        episode: RecoveryEpisode,
        evidence: EvidenceBundle?,
        research: ResearchBrief?,
        limit: Int
    ): List<RecoveryMove>? {
        val gateway = thinker ?: return null
        val signal = episode.signal
        val prompt = buildString {
            append("Failure class: ").append(signal.failureClass.name).append('\n')
            append("Target: ").append(signal.target.displayName).append(" · tool: ").append(signal.tool).append('\n')
            append("Error: ").append(ResilienceRedactor.redact(signal.errorLine, 320)).append('\n')
            append("Command: ").append(ResilienceRedactor.redact(signal.command, 320)).append('\n')
            if (episode.attempts.isNotEmpty()) {
                append("Already tried (do not repeat):\n")
                episode.attempts.forEach { append("  - ").append(it.kind.name).append(": ")
                    .append(if (it.succeeded == true) "worked" else "failed").append('\n') }
            }
            val learned = planner.describe(episode)
            if (learned.isNotBlank()) append("\nCurrent state:\n").append(learned.take(1_500)).append('\n')
            if (evidence != null) {
                append("\n=== UNTRUSTED PROBE EVIDENCE ===\n").append(evidence.toPromptContext(3_000)).append('\n')
            }
            if (research != null && research.sources.isNotEmpty()) {
                append("\n=== UNTRUSTED WEB EVIDENCE ===\n").append(research.toPromptContext(2_500)).append('\n')
            }
            append("\n").append(LateralThinking.curiosityPrompt(signal.failureClass, signal.tool)).append('\n')
        }
        val raw = gateway.complete(STUCK_SYSTEM, prompt, 800, 0.35)
        val json = JsonLite.parseObject(raw) ?: return null
        val list = json.arr("moves")
        val moves = list.mapNotNull { entry ->
            val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: return@mapNotNull null
            val kind = RecoveryKind.fromName(map.str("kind")) ?: return@mapNotNull null
            val instruction = map.strOrNull("instruction")?.take(400) ?: return@mapNotNull null
            RecoveryMove(
                kind = kind,
                title = map.strOrNull("title")?.take(120) ?: kind.label,
                rationale = map.strOrNull("rationale")?.take(300) ?: "Model-planned move.",
                instruction = instruction,
                safety = kind.safety,
                origin = MoveOrigin.MODEL
            )
        }
        return moves.take(limit).ifEmpty { null }
    }

    companion object {
        private val STUCK_SYSTEM = """
            You are Lucifer's recovery planner. A step failed and the normal playbook has not solved it.
            Think laterally: reconsider the target, the tool, the environment, and the problem statement itself.
            Rules:
            - Use only the given failure, the untrusted evidence, and the local learned history.
            - Never propose posting, messaging, paying, deleting user data, changing permission grants,
              disabling security controls, or contacting third parties.
            - Prefer one cheap read-only check that could falsify a hypothesis over a big repair.
            - Do not repeat a tactic listed as already tried.

            Return ONLY JSON: {"moves":[{"kind":"DIAGNOSE_ENVIRONMENT","title":"...",
            "instruction":"natural-language step","rationale":"why this is the best next probe"}]}
            Allowed kinds: DIAGNOSE_ENVIRONMENT, READ_LOGS, VERIFY_PREREQUISITES, WEB_RESEARCH, WAIT_AND_RETRY,
            ALTERNATE_TOOL, ALTERNATE_TARGET, SIMPLIFY_SCOPE, DECOMPOSE, QUESTION_ASSUMPTION, FIX_DEPENDENCY,
            FREE_RESOURCE, RECONFIGURE, ASK_USER.
        """.trimIndent()
    }
}
