package com.lucifer.mobile.resilience

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.lucifer.mobile.data.browser.LuciferBrowser
import com.lucifer.mobile.launcher.CommandTarget
import com.lucifer.mobile.memory.LocalConversationMemory
import com.lucifer.mobile.omni_bridge.llm.NemotronClient
import com.lucifer.mobile.skills.SkillSynthRuntime
import com.lucifer.mobile.terminal.TerminalExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the Recover panel and chat need, in one immutable snapshot. */
data class ResilienceSnapshot(
    val signal: FailureSignal? = null,
    val phase: RecoveryPhase = RecoveryPhase.CLASSIFIED,
    val plan: List<RecoveryMove> = emptyList(),
    val diagnosis: String = "",
    val evidenceSummary: String = "",
    val researchSummary: String = "",
    val status: String = "",
    val busy: Boolean = false,
    val attempts: Int = 0,
    val lessonSummary: String = "",
    val lessonCount: Int = 0,
    val standouts: List<String> = emptyList(),
    val storeHealthy: Boolean = true,
    val lastUpdated: Long = 0L
) {
    val hasFailure: Boolean get() = signal != null
    val nextMove: RecoveryMove? get() = plan.firstOrNull()
}

/**
 * Android-facing orchestration for the recovery loop.
 *
 * It owns the only three side-effecting adapters in the chain:
 *  - [TerminalExecutor] for fixed, catalog-verified read-only probes
 *  - [LuciferBrowser] for read-only HTTPS research
 *  - [NemotronClient] for planning/diagnosis (with deterministic fallbacks when absent)
 *
 * Lessons persist in app-private storage. All model/web/terminal work is bounded, and any
 * non-read-only repair still leaves this class as a *suggestion* that the existing router and
 * confirmation boundary must carry out.
 */
class ResilienceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val executor = TerminalExecutor(appContext)
    private val browser = LuciferBrowser(appContext)
    private val llm = NemotronClient(appContext)
    private val memory = LocalConversationMemory(appContext)

    private val lessonFile = File(appContext.filesDir, "resilience/lessons.json")
    private val atomicFile = AtomicFile(lessonFile)

    private val thinker = object : RecoveryThinker {
        override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Double): String {
            val reply = llm.chat(
                listOf("system" to system, "user" to user),
                maxTokens = maxTokens,
                temperature = temperature,
                requestTimeoutMs = 45_000
            )
            // NemotronClient reports failures as a "[LLM] ..." line; treat it as "no model".
            return if (reply.startsWith("[LLM]")) "" else reply
        }
    }

    private val ledger: LearningLedger
    private var storeHealthy = true
    private val storeError: String?
    private val planner: RecoveryPlanner
    private val coach: RecoveryCoach
    private val diagnosisEngine: DiagnosisEngine

    private var episode: RecoveryEpisode? = null
    private var lastEvidence: EvidenceBundle? = null
    private var lastResearch: ResearchBrief? = null

    /** Keeps a repeating failure from writing a memory row on every single attempt. */
    private val memorizedSignatures = LinkedHashSet<String>()

    private val _state = MutableStateFlow(ResilienceSnapshot())
    val state: StateFlow<ResilienceSnapshot> = _state.asStateFlow()

    init {
        val loaded = runCatching { readLedger() }
        val failureMessage = loaded.exceptionOrNull()?.let { it.message ?: "lesson store unreadable" }
        val result = loaded.getOrNull()
        storeHealthy = failureMessage == null
        storeError = failureMessage
        ledger = result ?: LearningLedger()
        planner = RecoveryPlanner(ledger)
        coach = RecoveryCoach(thinker, planner)
        diagnosisEngine = DiagnosisEngine(thinker, ledger)
        refresh(status = failureMessage?.let { "Lesson store issue: $it — new lessons will not be saved." } ?: "Ready.")
    }

    // ───────────────────────── capture ─────────────────────────

    /** Classify a finished command and keep it as the current failure when it actually failed. */
    @Synchronized
    fun captureOutcome(outcome: CommandOutcome): FailureSignal? {
        val signal = FailureClassifier.classify(outcome) ?: return null
        val current = episode
        if (current != null && current.signal.key == signal.key && !current.isFinished) {
            refresh(status = "The same failure repeated: ${signal.summary}")
            return signal
        }
        episode = planner.startEpisode(signal)
        lastEvidence = null
        lastResearch = null
        if (memorizedSignatures.add(signal.key)) {
            if (memorizedSignatures.size > 50) {
                memorizedSignatures.remove(memorizedSignatures.first())
            }
            appendMemory(
                "Recovery started: ${signal.failureClass.label}",
                "Tool ${signal.tool} failed on ${signal.target.displayName}. ${ResilienceRedactor.redact(signal.errorLine, 400)}",
                importance = 72
            )
        }
        refresh(
            status = "Captured ${signal.failureClass.label}. Investigate, research, or ask for a plan.",
            diagnosis = "",
            evidence = "",
            research = ""
        )
        return signal
    }

    @Synchronized
    fun reset(status: String = "Cleared the current failure. The lesson ledger is unchanged.") {
        episode = null
        lastEvidence = null
        lastResearch = null
        refresh(status = status, diagnosis = "", evidence = "", research = "")
    }

    // ───────────────────────── investigation ─────────────────────────

    /** Run the fixed read-only probe set for the current failure on its own target. */
    suspend fun investigate(): EvidenceBundle? = withContext(Dispatchers.IO) {
        val current = episode ?: run {
            refresh(status = "Nothing to investigate yet. Run a command that fails, or describe the problem.")
            return@withContext null
        }
        setBusy(true, "Running read-only probes on ${current.signal.target.displayName}…")
        // Four probes keeps one investigation cheap even on a slow SSH target.
        val probes = DiagnosticCatalog.forSignal(current.signal, maxProbes = 4)
        val results = mutableListOf<ProbeResult>()
        for (probe in probes) {
            if (!DiagnosticCatalog.isReadOnlyCommand(probe.command)) {
                Log.w(TAG, "Refused a probe that is not in the catalog: ${probe.id}")
                continue
            }
            val started = System.currentTimeMillis()
            val lines = try {
                executor.execute(probe.command, probe.target.toCommandTarget().launcherIndex)
            } catch (error: Exception) {
                listOf("[ERROR] probe failed: ${error.message ?: error.javaClass.simpleName}")
            }
            val exit = Regex("\\[exit\\s+(-?\\d+)]").find(lines.lastOrNull().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            results.add(
                ProbeResult(
                    probe = probe,
                    lines = lines.take(probe.maxLines),
                    exitCode = exit,
                    startedAt = started,
                    durationMs = System.currentTimeMillis() - started
                )
            )
        }
        if (episode?.id != current.id) {
            refresh(status = "A newer failure replaced this investigation; the stale evidence was discarded.")
            return@withContext null
        }
        val bundle = EvidenceBundle(current.signal, results)
        lastEvidence = bundle
        episode = planner.mark(current, RecoveryPhase.INVESTIGATING, "Ran ${results.size} read-only probe(s).")
        if (bundle.probeCount == 0) {
            refresh(status = "No read-only probe was available for this failure and target.")
            return@withContext bundle
        }
        refresh(
            status = "Collected ${bundle.probeCount} probe result(s). ${bundle.highlights().firstOrNull().orEmpty()}",
            evidence = bundle.highlights().joinToString("\n")
        )
        bundle
    }

    /** Ask the model (or the deterministic fallback) what the evidence means. */
    suspend fun diagnose(): Diagnosis? = withContext(Dispatchers.IO) {
        val current = episode ?: return@withContext null
        val bundle = lastEvidence ?: EvidenceBundle(current.signal, emptyList())
        setBusy(true, "Reasoning about the evidence…")
        val diagnosis = try {
            diagnosisEngine.diagnose(bundle)
        } catch (error: Exception) {
            refresh(status = "Diagnosis failed: ${error.message ?: error.javaClass.simpleName}")
            return@withContext null
        }
        if (episode?.id != current.id) {
            refresh(status = "A newer failure replaced this diagnosis; the stale result was discarded.")
            return@withContext null
        }
        episode = planner.withDiagnosis(current, diagnosis)
        // When the evidence is thin, go and read the docs instead of guessing. This is the
        // "encouraged" research reflex: at most once per episode, read-only, and bounded.
        var researchedSources = 0
        if (shouldConsultDocs(diagnosis) && lastResearch == null) {
            setBusy(true, "Diagnosis is thin — checking public documentation (read-only)…")
            val brief = runCatching { research(question = null) }.getOrNull()
            researchedSources = brief?.sources?.size ?: 0
        }
        refresh(
            status = buildString {
                append(
                    if (diagnosis.source == DiagnosisSource.MODEL) {
                        "Diagnosis ready (model-assisted, confidence ${diagnosis.confidence})."
                    } else {
                        "Heuristic diagnosis ready — no model was reachable, so this is evidence-only."
                    }
                )
                if (researchedSources > 0) append(" Also pulled $researchedSources public source(s) because confidence was low.")
                else if (shouldConsultDocs(diagnosis)) append(" Public docs were unreachable; the plan does not rely on them.")
            },
            diagnosis = diagnosis.render()
        )
        diagnosis
    }

    /** Low-confidence or evidence-only diagnoses are exactly when outside reading helps most. */
    private fun shouldConsultDocs(diagnosis: Diagnosis): Boolean {
        val thin = diagnosis.source == DiagnosisSource.HEURISTIC || diagnosis.confidence == "low"
        val docFriendly = when (episode?.signal?.failureClass) {
            FailureClass.TERMINAL_SETUP, FailureClass.AUTH -> false
            else -> true
        }
        return thin && docFriendly
    }

    // ───────────────────────── web research ─────────────────────────

    /**
     * Read-only web research. Defaults to the failure text, but a user question always wins.
     * Only public HTTPS pages are fetched; robots.txt, rate limit, and page budget are enforced.
     */
    suspend fun research(question: String? = null): ResearchBrief? = withContext(Dispatchers.IO) {
        val current = episode
        val asked = question?.trim().orEmpty()
        val topic = when {
            asked.isNotBlank() -> asked
            current != null -> "${current.signal.errorLine} ${current.signal.tool} fix"
            else -> return@withContext null
        }
        setBusy(true, "Searching public sources for: ${topic.take(90)}")
        val agent = ResearchAgent(
            fetcher = object : PageFetcher {
                override suspend fun fetch(url: String): FetchedPage {
                    val result = browser.navigate(url, timeoutMs = 15_000)
                    return if (result.success) {
                        FetchedPage(url = result.url, status = 200, body = result.html.ifBlank { result.text })
                    } else {
                        FetchedPage(url = url, status = 0, body = "", error = result.error.ifBlank { "fetch failed" })
                    }
                }
            },
            sleeper = { millis -> delay(millis.coerceIn(0, 5_000)) },
            maxPages = 6,
            maxResultPages = 2
        )
        val brief = try {
            agent.research(
                ResearchAgent.Request(
                    question = topic,
                    errorText = current?.signal?.errorLine.orEmpty(),
                    hints = current?.let { listOf("${it.signal.tool} ${it.signal.failureClass.label}") }.orEmpty()
                )
            )
        } catch (error: Exception) {
            refresh(status = "Research failed: ${error.message ?: error.javaClass.simpleName}")
            return@withContext null
        }
        lastResearch = brief
        if (current != null) {
            // Only attach the research if the same episode is still current.
            if (episode?.id == current.id) episode = planner.withResearch(current, brief)
        }
        if (brief.sources.isNotEmpty()) {
            appendMemory(
                "Web research: ${topic.take(120)}",
                brief.sources.take(4).joinToString("\n") { "${it.title} — ${it.url}\n${it.snippet.take(300)}" },
                importance = 60
            )
        }
        refresh(
            status = if (brief.sources.isEmpty()) {
                "No public sources were retrievable. ${brief.notes.firstOrNull().orEmpty()}"
            } else {
                "Found ${brief.sources.size} source(s) across ${brief.pagesFetched} page fetch(es)."
            },
            research = brief.render()
        )
        brief
    }

    // ───────────────────────── planning ─────────────────────────

    suspend fun plan(): List<RecoveryMove> = withContext(Dispatchers.IO) {
        val current = episode ?: return@withContext emptyList()
        setBusy(true, "Choosing the cheapest move that could falsify the current theory…")
        val moves = try {
            coach.plan(current, lastEvidence, lastResearch, limit = 4)
        } catch (error: Exception) {
            refresh(status = "Planning failed: ${error.message ?: error.javaClass.simpleName}")
            return@withContext emptyList()
        }
        if (episode?.id != current.id) {
            refresh(status = "A newer failure replaced this plan; the stale result was discarded.")
            return@withContext emptyList()
        }
        val advanced = planner.withPlan(
            if (current.diagnosis == null && lastEvidence != null) current.copy(phase = RecoveryPhase.PLANNING) else current,
            moves
        )
        episode = advanced
        if (moves.isEmpty() && advanced.phase == RecoveryPhase.EXHAUSTED) {
            appendMemory(
                "Recovery exhausted: ${current.signal.failureClass.label}",
                "No untried tactic remained. ${current.signal.summary}",
                importance = 65
            )
        }
        refresh(
            status = when {
                moves.isEmpty() -> "Every known tactic has been tried. New context would be needed."
                else -> "${moves.size} move(s) ready. The first is: ${moves.first().title}"
            }
        )
        moves
    }

    /** Record what actually happened for a move, which is what teaches the ledger. */
    @Synchronized
    fun recordAttempt(move: RecoveryMove, succeeded: Boolean, note: String) {
        val current = episode ?: return
        episode = planner.recordAttempt(current, move, succeeded, note)
        // The tactic that fixed this class of failure on this target is worth generalising beyond the
        // recovery loop: it becomes a procedure skill the model is told about on later turns.
        try {
            SkillSynthRuntime.learnProcedure(
                appContext,
                current.signal.failureClass.label,
                current.signal.target.displayName,
                move.kind.label,
                succeeded
            )
        } catch (_: Exception) {
            // Learning must never break the thing it is learning from.
        }
        persist()
        if (succeeded) {
            appendMemory(
                "Recovery worked: ${move.kind.label}",
                "Failure: ${current.signal.summary}\nRemedy: ${move.instruction.take(300)}\nObserved: ${ResilienceRedactor.redact(note, 400)}",
                importance = 85
            )
        }
        refresh(
            status = if (succeeded) {
                "Recorded success for ${move.kind.label}. That lesson now outranks what failed."
            } else {
                "Recorded that ${move.kind.label} did not help. Next plan will avoid it."
            }
        )
    }

    /**
     * The user carried out advice themselves and reports the outcome. Recorded against the next
     * planned move (or the last attempted tactic) so manual work still trains the ledger.
     */
    @Synchronized
    fun recordManualOutcome(succeeded: Boolean, note: String = "Outcome reported manually by the user.") {
        val current = episode ?: return
        val move = current.plan.firstOrNull()
            ?: current.attempts.lastOrNull()?.let { attempt ->
                RecoveryMove(
                    kind = attempt.kind,
                    title = attempt.kind.label,
                    rationale = "Reported manually after the last planned move.",
                    instruction = "Reported by the user."
                )
            }
            ?: RecoveryMove(
                kind = RecoveryKind.ASK_USER,
                title = "Reported manually",
                rationale = "No specific move was awaiting an outcome.",
                instruction = "Reported by the user."
            )
        recordAttempt(move, succeeded, note)
    }

    // ───────────────────────── lessons ─────────────────────────

    fun lessonOverview(): String {
        val standouts = ledger.standouts(limit = 5)
        val current = episode
        return buildString {
            append("Lessons learned: ").append(ledger.size).append(" outcome(s) from real runs.\n")
            if (standouts.isEmpty()) {
                append("Nothing learned yet — Lucifer records an outcome every time a recovery move is tried.\n")
            } else {
                append("Tactics that usually work here:\n")
                standouts.forEach {
                    append("  • ").append(it.kind.label).append(" — ")
                        .append(it.stat.successes).append('/').append(it.stat.attempts)
                        .append(" successful\n")
                }
            }
            if (current != null) {
                append("This failure: ").append(ledger.summary(current.signal.key).text).append('\n')
            }
        }.trim()
    }

    @Synchronized
    fun clearLessons(): String {
        ledger.clear()
        persist()
        episode = episode?.copy(attempts = emptyList(), plan = episode?.plan.orEmpty())
        refresh(status = "Lesson ledger cleared.")
        return "Lesson ledger cleared. Lucifer will relearn from new outcomes."
    }

    @Synchronized
    fun forgetCurrentSignature(): String {
        val current = episode ?: return "No active failure to forget."
        val removed = ledger.forget(current.signal.key)
        persist()
        refresh(status = "Forgot $removed lesson(s) for this failure signature.")
        return "Forgot $removed lesson(s) for this failure signature."
    }

    private fun persist() {
        if (!storeHealthy) return
        try {
            val json = ledger.encode()
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_STORE_BYTES) { "lesson store limit exceeded" }
            lessonFile.parentFile?.mkdirs()
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomicFile.startWrite()
                stream.write(json.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (error: Exception) {
                stream?.let(atomicFile::failWrite)
                throw error
            }
        } catch (error: Exception) {
            storeHealthy = false
            refresh(status = "Could not save lessons: ${error.message ?: "storage error"}. Further lessons stay in memory.")
        }
    }

    private fun readLedger(): LearningLedger {
        val backup = File(lessonFile.path + ".bak")
        if (!lessonFile.isFile && !backup.isFile) return LearningLedger()
        val size = maxOf(lessonFile.length(), backup.length())
        require(size in 1L..MAX_STORE_BYTES) { "lesson store is invalid or oversized" }
        val raw = atomicFile.openRead().bufferedReader().use { it.readText() }
        return LearningLedger.decode(raw)
            ?: throw IllegalStateException("lesson store schema is not recognized")
    }

    // ───────────────────────── plumbing ─────────────────────────

    private fun setBusy(busy: Boolean, status: String) {
        _state.value = _state.value.copy(busy = busy, status = status, lastUpdated = System.currentTimeMillis())
    }

    private fun refresh(
        status: String? = null,
        diagnosis: String? = null,
        evidence: String? = null,
        research: String? = null
    ) {
        val current = episode
        _state.value = ResilienceSnapshot(
            signal = current?.signal,
            phase = current?.phase ?: RecoveryPhase.CLASSIFIED,
            plan = current?.plan.orEmpty(),
            diagnosis = diagnosis ?: _state.value.diagnosis,
            evidenceSummary = evidence ?: _state.value.evidenceSummary,
            researchSummary = research ?: _state.value.researchSummary,
            status = status ?: _state.value.status,
            busy = false,
            attempts = current?.attempts?.size ?: 0,
            lessonSummary = current?.let { ledger.summary(it.signal.key).text } ?: _state.value.lessonSummary,
            lessonCount = ledger.size,
            standouts = ledger.standouts(limit = 3).map { "${it.kind.label} ${it.stat.successes}/${it.stat.attempts}" },
            storeHealthy = storeHealthy,
            lastUpdated = System.currentTimeMillis()
        )
        if (storeError != null && !storeHealthy) {
            _state.value = _state.value.copy(status = "Lesson store issue: $storeError")
        }
    }

    private fun appendMemory(title: String, details: String, importance: Int) {
        try {
            memory.rememberFinding(title, details, importance)
        } catch (error: Exception) {
            Log.i(TAG, "Memory write skipped: ${error.javaClass.simpleName}")
        }
    }

    private fun ResilienceTarget.toCommandTarget(): CommandTarget = when (this) {
        ResilienceTarget.LOCAL -> CommandTarget.LOCAL
        ResilienceTarget.TERMUX -> CommandTarget.TERMUX
        ResilienceTarget.VM -> CommandTarget.VM
        ResilienceTarget.SHIZUKU -> CommandTarget.SHIZUKU
    }

    companion object {
        private const val TAG = "LuciferResilience"
        private const val MAX_STORE_BYTES = 256 * 1024L

        fun targetOf(target: CommandTarget): ResilienceTarget = when (target) {
            CommandTarget.LOCAL -> ResilienceTarget.LOCAL
            CommandTarget.TERMUX -> ResilienceTarget.TERMUX
            CommandTarget.VM -> ResilienceTarget.VM
            CommandTarget.SHIZUKU -> ResilienceTarget.SHIZUKU
        }

        /** Build the outcome Lucifer's classifier expects from a finished terminal action. */
        fun outcomeOf(target: CommandTarget, command: String, lines: List<String>): CommandOutcome =
            CommandOutcome(target = targetOf(target), command = command, lines = lines)

        /**
         * Whether a terminal result looks like success, used when recording a recovery attempt.
         * Anything ambiguous is treated as "not confirmed", never as a win.
         */
        fun looksSuccessful(lines: List<String>): Boolean {
            val exit = Regex("\\[exit\\s+(-?\\d+)]").find(lines.asReversed().joinToString("\n"))
                ?.groupValues?.get(1)?.toIntOrNull()
            if (exit != null) return exit == 0
            return !FailureClassifier.looksLikeFailure(
                CommandOutcome(ResilienceTarget.LOCAL, "", lines)
            )
        }
    }
}
