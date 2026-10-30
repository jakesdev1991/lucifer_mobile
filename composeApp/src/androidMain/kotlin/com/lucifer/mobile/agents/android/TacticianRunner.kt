package com.lucifer.mobile.agents.android

import android.content.Context
import android.util.Log
import com.lucifer.mobile.agents.AgentPack
import com.lucifer.mobile.agents.ComposeRequest
import com.lucifer.mobile.agents.SkillComposer
import com.lucifer.mobile.agents.SkillRef
import com.lucifer.mobile.agents.Thinker
import com.lucifer.mobile.omni_bridge.llm.NemotronClient
import com.lucifer.mobile.tactician.Opportunity
import com.lucifer.mobile.tactician.McpToolProposals
import com.lucifer.mobile.tactician.OpportunityCategory
import com.lucifer.mobile.tactician.ProposalLedger
import com.lucifer.mobile.tactician.ProposalStatus
import com.lucifer.mobile.tactician.TacticianEngine
import com.lucifer.mobile.tactician.TacticianReport
import com.lucifer.mobile.tactician.ObservationKind
import java.io.File

/** One model call, counted in the observation ledger so the daily budget is real. */
class LlmThinker(private val context: Context, private val client: NemotronClient = NemotronClient(context)) : Thinker {

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Double): String {
        ObservationBus.record(
            context, ObservationKind.MODEL,
            "tactician model call (${user.length} char brief)",
            source = "tactician"
        )
        return client.chat(
            listOf("system" to system, "user" to user),
            maxTokens = maxTokens,
            temperature = temperature,
            requestTimeoutMs = 90_000,
            // A tactician pass is the app thinking about its own day, not a person asking.
            userInitiated = false
        )
    }
}

data class TacticianOutcome(
    val ok: Boolean,
    val message: String,
    val report: TacticianReport? = null,
    val newProposalCount: Int = 0
)

/** Runs one bounded tactician pass and writes the report. Shared by the service and the UI. */
class TacticianRunner(private val context: Context) {

    private val appContext = context.applicationContext
    private val store = PromptStore(appContext)
    private val collector = SignalCollector(appContext)

    suspend fun runOnce(trigger: String): TacticianOutcome {
        store.ensureDefaults()
        val now = System.currentTimeMillis()
        val budget = collector.defaultBudget()
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }
            .get(java.util.Calendar.HOUR_OF_DAY)
        val callsToday = TacticianEngine.callCountToday(
            ObservationBus.ledger(appContext).all(), now
        )
        val decision = budget.allowsRun(collector.lastRunAt(), callsToday, now, hour)
        if (!decision.allowed) {
            return TacticianOutcome(false, "Tactician pass skipped: ${decision.reason}.")
        }

        val added = collector.collect()
        val input = collector.detectionInput(store, now, budget)
        val existing = TacticianEngine.loadLedger(collector.readLedger())
        val engine = TacticianEngine(LlmThinker(appContext))
        val run = try {
            engine.run(input, existing)
        } catch (error: Exception) {
            Log.e(TAG, "tactician pass failed", error)
            ObservationBus.recordFailure(appContext, ObservationKind.AGENT_RUN, "tactician pass failed", error.message ?: "")
            return TacticianOutcome(false, "Tactician pass failed: ${error.message ?: "unknown error"}.")
        }

        collector.writeLedger(run.ledger.encode())
        collector.markRan(now)
        writeReport(run.report)
        ObservationBus.record(
            appContext, ObservationKind.AGENT_RUN,
            "tactician pass ($trigger): ${run.report.newProposalCount} new proposal(s)",
            detail = run.report.situation.take(300),
            source = "general", tags = listOf("general")
        )
        val summary = if (run.report.newProposalCount == 0) {
            "No new proposals. ${run.report.situation.take(160)}"
        } else {
            "${run.report.newProposalCount} new proposal(s) · top: ${run.report.proposals.firstOrNull()?.title ?: "—"}"
        }
        return TacticianOutcome(true, "$summary (+$added observations)", run.report, run.report.newProposalCount)
    }

    fun ledger(): ProposalLedger = TacticianEngine.loadLedger(collector.readLedger())

    fun latestReport(): String? = try {
        val file = File(appContext.filesDir, "tactician/latest.md")
        if (file.isFile && file.length() in 1..256_000L) file.readText() else null
    } catch (_: Exception) {
        null
    }

    fun reportFiles(limit: Int = 10): List<File> = try {
        File(appContext.filesDir, "tactician/reports").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedByDescending { it.name }
            ?.take(limit)
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeMcpDraft(draft: com.lucifer.mobile.tactician.McpToolDraft): File? = try {
        val dir = File(appContext.filesDir, "tactician/mcp-drafts")
        dir.mkdirs()
        val file = File(dir, "${System.currentTimeMillis()}_${draft.name}.json")
        file.writeText(draft.toJson())
        file
    } catch (error: Exception) {
        Log.w(TAG, "mcp draft write failed", error)
        null
    }

    private fun writeReport(report: TacticianReport) {
        try {
            val root = File(appContext.filesDir, "tactician")
            val reports = File(root, "reports")
            reports.mkdirs()
            val markdown = report.toMarkdown()
            File(reports, "${report.generatedAt}.md").writeText(markdown)
            File(root, "latest.md").writeText(markdown)
        } catch (error: Exception) {
            Log.w(TAG, "report write failed", error)
        }
    }

    /** Approve a proposal. Agent-shaped proposals become a real agent class; others stay advisory. */
    suspend fun approve(fingerprint: String): PromptSaveResult {
        val ledger = ledger()
        val proposal = ledger.find(fingerprint)
            ?: return PromptSaveResult(false, "That proposal no longer exists.")
        if (proposal.status != ProposalStatus.PROPOSED) {
            return PromptSaveResult(false, "Already ${proposal.status.name.lowercase()}.")
        }
        ledger.setStatus(fingerprint, ProposalStatus.APPROVED)
        collector.writeLedger(ledger.encode())

        if (proposal.category == OpportunityCategory.MCP_TOOL) {
            val draft = McpToolProposals.from(proposal)
                ?: return PromptSaveResult(false, "That proposal is not a memory-tool draft.")
            val problems = McpToolProposals.problems(draft)
            if (problems.isNotEmpty()) return PromptSaveResult(false, "Draft rejected: ${problems.joinToString("; ")}.")
            val file = writeMcpDraft(draft)
                ?: return PromptSaveResult(false, "Could not write the draft to app-private storage.")
            ledger.setStatus(fingerprint, ProposalStatus.APPLIED)
            collector.writeLedger(ledger.encode())
            ObservationBus.record(
                appContext, ObservationKind.MCP,
                "read-only MCP tool draft written: ${draft.name}",
                detail = "Not installed. The user must add it to the Termux memory server.",
                source = "general", tags = listOf("draft")
            )
            return PromptSaveResult(
                true,
                "Draft saved to files/tactician/mcp-drafts/${file.name}. Nothing was installed — follow the checklist in that file to add the tool in Termux."
            )
        }
        if (proposal.category == OpportunityCategory.AGENT || proposal.category == OpportunityCategory.SKILL) {
            val pack = composeAgentFor(proposal)
            val result = store.createAgent(pack)
            if (result.saved) {
                ledger.setStatus(fingerprint, ProposalStatus.APPLIED)
                collector.writeLedger(ledger.encode())
            }
            return result
        }
        return PromptSaveResult(true, "Approved: ${proposal.title}. Nothing was executed; apply it yourself or ask Lucifer.")
    }

    fun reject(fingerprint: String, expire: Boolean = false): PromptSaveResult {
        val ledger = ledger()
        val proposal = ledger.find(fingerprint) ?: return PromptSaveResult(false, "That proposal no longer exists.")
        val status = if (expire) ProposalStatus.EXPIRED else ProposalStatus.REJECTED
        ledger.setStatus(fingerprint, status)
        collector.writeLedger(ledger.encode())
        return PromptSaveResult(true, "${proposal.title} ${status.name.lowercase()}.")
    }

    fun clearResolved(): PromptSaveResult {
        val ledger = ledger()
        val before = ledger.size
        ledger.clear(ProposalStatus.REJECTED)
        ledger.clear(ProposalStatus.EXPIRED)
        collector.writeLedger(ledger.encode())
        return PromptSaveResult(true, "Cleared ${before - ledger.size} resolved proposal(s).")
    }

    /**
     * Compose a new agent class from a goal, compiling relevant reference skills and the current
     * evidence into the mandate. The model is used only when Lucifer is the author and the budget
     * allows it; the deterministic draft is always the fallback.
     */
    suspend fun composeAgent(name: String, goal: String, author: com.lucifer.mobile.agents.Author): AgentPack {
        val existingIds = store.registry().packs.map { it.id }.toSet()
        val skills = relatedSkills(goal)
        val notes = evidenceNotes(goal)
        val request = ComposeRequest(
            name = name,
            goal = goal,
            role = com.lucifer.mobile.agents.AgentRole.CUSTOM,
            skills = skills,
            notes = notes,
            author = author
        )
        val draft = if (author == com.lucifer.mobile.agents.Author.LUCIFER) {
            runCatching { SkillComposer.composeWithModel(request, LlmThinker(appContext), existingIds) }
                .getOrElse { SkillComposer.compose(request, existingIds) }
        } else {
            SkillComposer.compose(request, existingIds)
        }
        return draft.toPack(enabled = true).copy(author = author)
    }

    /** Recovery and proposal evidence that is relevant to a new agent's remit. */
    fun evidenceNotes(goal: String, limit: Int = 5): List<String> {
        val words = goal.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        val fromRecovery = runCatching {
            SignalCollector(appContext).recoveryDigest()
                .filter { digest ->
                    words.any { word ->
                        digest.failureClass.contains(word, true) || digest.signature.contains(word, true)
                    }
                }
                .take(3)
                .map { "${it.failureClass} · ${it.attempts} attempts · ${it.successes} succeeded · note: ${it.note.take(120)}" }
        }.getOrDefault(emptyList())
        val fromProposals = ledger().all()
            .filter { proposal -> words.any { word -> proposal.title.contains(word, true) } }
            .take(2)
            .map { "${it.title} · ${it.why.take(140)}" }
        return (fromRecovery + fromProposals).take(limit)
    }

    /** Build the agent class a proposal implies. */
    private suspend fun composeAgentFor(proposal: Opportunity): AgentPack = composeAgent(
        name = "",
        goal = "${proposal.title}. ${proposal.action}",
        author = com.lucifer.mobile.agents.Author.LUCIFER
    )

    /** Reference skills relevant to a goal, compiled into a new agent's mandate. */
    fun relatedSkills(query: String, limit: Int = 4): List<SkillRef> = try {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.take(6)
        val pack = appContext.assets.open("skills/skill_library.json").bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(pack)
        val out = mutableListOf<SkillRef>()
        val packs = root.optJSONArray("packs") ?: org.json.JSONArray()
        outer@ for (index in 0 until packs.length()) {
            val packObject = packs.optJSONObject(index) ?: continue
            val title = packObject.optString("title")
            val entries = packObject.optJSONArray("skills") ?: continue
            for (skillIndex in 0 until entries.length()) {
                val skill = entries.optJSONObject(skillIndex) ?: continue
                val name = skill.optString("name")
                val summary = skill.optString("summary")
                val haystack = "$name $summary".lowercase()
                if (words.isNotEmpty() && words.none { haystack.contains(it) }) continue
                out.add(
                    SkillRef(
                        id = skill.optString("id"),
                        name = name,
                        summary = summary,
                        readiness = skill.optString("readiness", "REFERENCE_ONLY"),
                        packTitle = title,
                        safetyBoundary = packObject.optString("safetyBoundary")
                    )
                )
                if (out.size >= limit) break@outer
            }
        }
        out
    } catch (error: Exception) {
        Log.w(TAG, "skill lookup failed", error)
        emptyList()
    }

    companion object {
        private const val TAG = "TacticianRunner"
    }
}
