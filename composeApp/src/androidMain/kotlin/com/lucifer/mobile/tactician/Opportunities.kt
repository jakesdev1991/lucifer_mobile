package com.lucifer.mobile.tactician

import com.lucifer.mobile.agents.AgentPack
import com.lucifer.mobile.agents.AgentRegistry
import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.resilience.ResilienceText
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.str
import com.lucifer.mobile.resilience.stringList

/**
 * The General's output: ranked, evidence-backed structural recommendations.
 *
 * Nothing here executes. A proposal becomes an agent class or a prompt edit only when the user
 * approves it, which keeps "always working in the background" from meaning "always changing things".
 */
enum class OpportunityCategory(val label: String, val baseScore: Int) {
    RELIABILITY("reliability", 82),
    SKILL("new skill", 74),
    AGENT("agent roster", 70),
    MCP_TOOL("memory MCP", 66),
    WORKFLOW("workflow", 62),
    PROMPT("prompt", 58),
    COST("cost / schedule", 54),
    PRIVACY("privacy", 60),
    UX("usability", 46);

    companion object {
        fun from(raw: String?): OpportunityCategory? =
            entries.firstOrNull { it.name == raw || it.label.equals(raw, ignoreCase = true) }
    }
}

enum class Autonomy(val label: String) {
    READ_ONLY("read-only"),
    SUGGESTION("suggestion"),
    APPROVAL_REQUIRED("approval required")
}

enum class ProposalStatus { PROPOSED, APPROVED, REJECTED, APPLIED, EXPIRED }

data class Opportunity(
    val id: String,
    val category: OpportunityCategory,
    val title: String,
    val why: String,
    val evidence: List<String>,
    val action: String,
    val score: Int,
    val autonomy: Autonomy = Autonomy.APPROVAL_REQUIRED,
    val status: ProposalStatus = ProposalStatus.PROPOSED,
    val createdAt: Long = System.currentTimeMillis(),
    val origin: String = "detector",
    /** Stable identity across runs so the same finding is not duplicated. */
    val fingerprint: String = ""
) {
    fun withFingerprint(): Opportunity {
        if (fingerprint.isNotBlank()) return this
        val key = ResilienceText.stableKey(listOf(category.name, title.lowercase()))
        return copy(id = if (id.isBlank()) "p$key" else id, fingerprint = key)
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "cat" to category.name,
        "title" to title,
        "why" to why,
        "ev" to evidence,
        "act" to action,
        "score" to score,
        "auto" to autonomy.name,
        "status" to status.name,
        "at" to createdAt,
        "origin" to origin,
        "fp" to fingerprint
    )

    companion object {
        fun fromJson(map: Map<String, Any?>): Opportunity? {
            val category = OpportunityCategory.from(map.str("cat")) ?: return null
            val title = map.str("title").take(140)
            if (title.isBlank()) return null
            return Opportunity(
                id = map.str("id").take(40),
                category = category,
                title = title,
                why = map.str("why").take(600),
                evidence = map.stringList("ev").map { it.take(60) }.take(8),
                action = map.str("act").take(600),
                score = map.int("score").coerceIn(0, 100),
                autonomy = Autonomy.entries.firstOrNull { it.name == map.str("auto") } ?: Autonomy.APPROVAL_REQUIRED,
                status = ProposalStatus.entries.firstOrNull { it.name == map.str("status") } ?: ProposalStatus.PROPOSED,
                createdAt = map.long("at"),
                origin = map.str("origin").take(20),
                fingerprint = map.str("fp").take(24)
            ).withFingerprint()
        }
    }
}

/** Bounded, de-duplicated proposal inbox with explicit status transitions. */
class ProposalLedger(initial: List<Opportunity> = emptyList(), private val maxEntries: Int = MAX_ENTRIES) {

    private val items = LinkedHashMap<String, Opportunity>()

    init {
        initial.takeLast(maxEntries).forEach { items[it.fingerprint] = it }
    }

    val size: Int get() = items.size

    fun all(): List<Opportunity> = items.values.sortedByDescending { it.score }

    fun byStatus(status: ProposalStatus): List<Opportunity> = all().filter { it.status == status }

    fun pending(): List<Opportunity> = byStatus(ProposalStatus.PROPOSED)

    fun find(fingerprint: String): Opportunity? = items[fingerprint]

    /**
     * Merge a detector pass. Returns the fingerprints that were new, so a run can report what
     * actually changed instead of re-announcing the backlog.
     */
    @Synchronized
    fun merge(candidates: List<Opportunity>): List<String> {
        val added = mutableListOf<String>()
        for (candidate in candidates) {
            val fresh = candidate.withFingerprint()
            val existing = items[fresh.fingerprint]
            if (existing == null) {
                items[fresh.fingerprint] = fresh
                added.add(fresh.fingerprint)
            } else if (existing.status == ProposalStatus.PROPOSED) {
                // Refresh evidence and score, but never resurrect a rejected or applied item.
                items[fresh.fingerprint] = existing.copy(
                    evidence = fresh.evidence,
                    why = fresh.why.ifBlank { existing.why },
                    score = fresh.score,
                    createdAt = fresh.createdAt
                )
            }
        }
        trim()
        return added
    }

    @Synchronized
    fun setStatus(fingerprint: String, status: ProposalStatus): Opportunity? {
        val existing = items[fingerprint] ?: return null
        val updated = existing.copy(status = status)
        items[fingerprint] = updated
        return updated
    }

    @Synchronized
    fun clear(status: ProposalStatus? = null) {
        if (status == null) {
            items.clear()
        } else {
            items.values.filter { it.status == status }.forEach { items.remove(it.fingerprint) }
        }
    }

    private fun trim() {
        while (items.size > maxEntries) {
            // Drop the oldest non-pending item first so unread proposals survive.
            val victim = items.values.filter { it.status != ProposalStatus.PROPOSED }
                .minByOrNull { it.createdAt }
                ?: items.values.minByOrNull { it.createdAt } ?: break
            items.remove(victim.fingerprint)
        }
    }

    fun encode(): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "schema" to SCHEMA,
            "proposals" to items.values.map { it.toJson() }
        )
    )

    companion object {
        const val SCHEMA = 1
        const val MAX_ENTRIES = 40

        fun decode(raw: String?): ProposalLedger? {
            if (raw.isNullOrBlank() || raw.length > 512_000) return null
            val root = JsonLite.parseObject(raw) ?: return null
            if (root.int("schema", -1) != SCHEMA) return null
            val list = root.arr("proposals").mapNotNull { entry ->
                val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
                map?.let { Opportunity.fromJson(it) }
            }
            return ProposalLedger(list)
        }
    }
}

/**
 * Hard limits on the General's own activity. A background strategist that can spend the user's API
 * budget without limit is a liability, so the budget is checked before every model call.
 */
data class AutonomyBudget(
    val maxModelCallsPerDay: Int = 40,
    val minMinutesBetweenRuns: Int = 20,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
    val maxReportChars: Int = 24_000
) {
    fun minuteOfDay(hour: Int, minute: Int): Int = hour * 60 + minute

    fun isQuietHour(hour: Int, minute: Int = 0): Boolean {
        val now = minuteOfDay(hour, minute)
        val start = minuteOfDay(quietStartHour, 0)
        val end = minuteOfDay(quietEndHour, 0)
        if (start == end) return false
        return if (start < end) now in start until end else now >= start || now < end
    }

    /** Whether one more run may start, given the last run and today's usage. */
    fun allowsRun(lastRunAt: Long, callsToday: Int, now: Long, hour: Int, minute: Int = 0): BudgetDecision {
        if (isQuietHour(hour, minute)) return BudgetDecision(false, "quiet hours ($quietStartHour:00-$quietEndHour:00)")
        if (lastRunAt > 0 && now - lastRunAt < minMinutesBetweenRuns * 60_000L) {
            val wait = (minMinutesBetweenRuns * 60_000L - (now - lastRunAt)) / 60_000L
            return BudgetDecision(false, "next run in ${wait}s".replace("s", " min"))
        }
        if (callsToday >= maxModelCallsPerDay) return BudgetDecision(false, "daily model budget reached ($callsToday/$maxModelCallsPerDay)")
        return BudgetDecision(true, "ok")
    }
}

data class BudgetDecision(val allowed: Boolean, val reason: String)

/** Everything the detector needs, gathered by the Android layer. */
data class DetectionInput(
    val observations: List<Observation>,
    val agents: AgentRegistry,
    val recovery: List<RecoveryDigest> = emptyList(),
    val agentRuns: Map<String, Int> = emptyMap(),
    val workflow: WorkflowSignals = WorkflowSignals(),
    val heartbeat: HeartbeatSignals = HeartbeatSignals(),
    val modelCallsToday: Int = 0,
    val budget: AutonomyBudget = AutonomyBudget(),
    val proposalsPending: Int = 0,
    val lastRunAt: Long = 0L,
    val now: Long = System.currentTimeMillis()
)

data class WorkflowSignals(
    val total: Int = 0,
    val active: Int = 0,
    val readyToComplete: Int = 0,
    val stalledOverDays: Int = 0
)

data class HeartbeatSignals(
    val enabled: Boolean = false,
    val cameraConsent: Boolean = false,
    val sessionsInWindow: Int = 0,
    val cameraSessionsInWindow: Int = 0,
    val lastSessionAt: Long = 0L,
    val customDirective: Boolean = false
)

/** Deterministic detectors. These run first and always, with or without a model. */
object OpportunityDetector {

    fun detect(input: DetectionInput): List<Opportunity> {
        val window = 14L * 24 * 60 * 60 * 1_000
        val recent = input.observations.filter { input.now - it.at in 0..window }
        val out = mutableListOf<Opportunity>()

        out += recoveryOpportunities(input)
        out += reliabilityOpportunities(input, recent)
        out += agentOpportunities(input, recent)
        out += heartbeatOpportunities(input)
        out += workflowOpportunities(input)
        out += costOpportunities(input, recent)
        out += workflowHygiene(input, recent)
        out += memoryToolOpportunity(input, recent)
        backlogOpportunity(input)?.let { out += it }

        return out.map { it.withFingerprint() }
            .distinctBy { it.fingerprint }
            .sortedByDescending { it.score }
    }

    // ── recovery / reliability ───────────────────────────────────────

    private fun recoveryOpportunities(input: DetectionInput): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val exhausted = input.recovery.filter { it.attempts >= 5 && it.successes == 0 }
        for (item in exhausted.take(2)) {
            out += opp(
                OpportunityCategory.RELIABILITY,
                "Unresolved failure keeps recurring: ${item.failureClass.lowercase()}",
                "Five recovery moves were tried and none worked. Repeating the same playbook wastes the user's time; this needs a durable fix, not another retry.",
                listOf(item.signature, "${item.attempts} attempts", "last ${
                    ObservationLedger.ageMinutes(item.lastAt, input.now)
                }"),
                90,
                "Create a repair workflow for this signature, or add the specific fix as a skill so the playbook learns it."
            )
        }
        val byClass = input.recovery.groupBy { it.failureClass }
            .filter { (_, items) -> items.size >= 3 }
            .maxByOrNull { it.value.size }
        if (byClass != null) {
            out += opp(
                OpportunityCategory.SKILL,
                "Repeated ${byClass.key.lowercase()} failures across ${byClass.value.size} signatures",
                "The same class of failure keeps appearing in different places. A focused playbook entry (or a dedicated agent) would fix a family of problems instead of one.",
                byClass.value.take(4).map { it.signature },
                78,
                "Compose an agent class from these signatures' evidence and let it own that failure class."
            )
        }
        return out
    }

    private fun reliabilityOpportunities(input: DetectionInput, recent: List<Observation>): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val failures = recent.filter { it.isFailure }
        val recoveriesStarted = recent.count { it.kind == ObservationKind.RECOVERY }
        if (failures.size >= 5 && recoveriesStarted == 0 && input.recovery.isEmpty()) {
            out += opp(
                OpportunityCategory.RELIABILITY,
                "Failures are not being captured by the recovery loop",
                "${failures.size} failure observations exist but no recovery episode or lesson was recorded, so Lucifer cannot learn from them.",
                failures.takeLast(4).map { it.id },
                85,
                "Check that every terminal path calls the recovery capture hook; this is an instrumentation bug, not a user problem."
            )
        }
        val sameTerminal = failures.filter { it.kind == ObservationKind.TERMINAL }
            .groupBy { it.summary.lowercase().take(60) }
            .filter { it.value.size >= 3 }
            .maxByOrNull { it.value.size }
        if (sameTerminal != null) {
            out += opp(
                OpportunityCategory.WORKFLOW,
                "The same command keeps failing",
                "'${sameTerminal.key}' failed ${sameTerminal.value.size} times. Either the step is wrong or the environment is, and repeating it will not change that.",
                sameTerminal.value.takeLast(3).map { it.id },
                74,
                "Turn this into a workflow step that checks prerequisites first, then run it once."
            )
        }
        val denied = failures.filter { it.summary.contains("permission", ignoreCase = true) }
        if (denied.size >= 3) {
            out += opp(
                OpportunityCategory.WORKFLOW,
                "Permission denials recur",
                "${denied.size} recent failures mention permissions. The work is probably running on the wrong target rather than lacking a grant.",
                denied.takeLast(3).map { it.id },
                70,
                "Add a preflight check that picks a target with the right access instead of retrying in place."
            )
        }
        return out
    }

    // ── agents ───────────────────────────────────────────────────────

    private fun agentOpportunities(input: DetectionInput, recent: List<Observation>): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val enabled = input.agents.enabled
        val runs = input.agentRuns
        val unused = enabled.filter { (runs[it.id] ?: 0) == 0 }
        if (unused.size >= 2) {
            out += opp(
                OpportunityCategory.AGENT,
                "${unused.size} agent classes have never run",
                "A roster nobody uses still costs attention every time Lucifer picks a specialist. Idle agents: ${unused.joinToString { it.id }}.",
                unused.map { it.id },
                64,
                "Disable or merge the idle agents, or wire a trigger that actually matches their work."
            )
        }
        val overlaps = input.agents.overlappingPairs()
        if (overlaps.isNotEmpty()) {
            val (a, b) = overlaps.first()
            out += opp(
                OpportunityCategory.AGENT,
                "Agent classes overlap: ${a.id} and ${b.id}",
                "They share triggers or skills, so routing between them is arbitrary and results will be inconsistent.",
                listOf(a.id, b.id),
                68,
                "Merge them into one class with the union of their skills, or split their triggers so each owns distinct work."
            )
        }
        if (enabled.size > 8) {
            out += opp(
                OpportunityCategory.AGENT,
                "The roster is growing faster than its use",
                "${enabled.size} enabled agent classes make selection noisy. Fewer, sharper specialists usually outperform a large roster.",
                enabled.take(6).map { it.id },
                60,
                "Keep the top five by usage and retire the rest."
            )
        }
        val broken = input.agents.problems
        if (broken.isNotEmpty()) {
            out += opp(
                OpportunityCategory.AGENT,
                "${broken.size} agent file(s) are invalid",
                broken.joinToString("; ") { "${it.id}: ${it.problem}" },
                broken.map { it.id },
                76,
                "Open the agent editor and fix or delete these files so routing stops skipping them."
            )
        }
        val neverRunButRequested = recent.filter { it.kind == ObservationKind.USER }.size
        if (enabled.size == 1 && neverRunButRequested >= 10) {
            out += opp(
                OpportunityCategory.AGENT,
                "One agent is doing every job",
                "The General is handling all ${neverRunButRequested} recent requests. Some work would benefit from a narrow specialist.",
                emptyList(),
                52,
                "Create one specialist for the most frequent request type and compare results before adding more."
            )
        }
        return out
    }

    // ── heartbeat / camera / privacy ─────────────────────────────────

    private fun heartbeatOpportunities(input: DetectionInput): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val hb = input.heartbeat
        if (!hb.enabled && input.observations.size >= 20) {
            out += opp(
                OpportunityCategory.UX,
                "Heartbeat is off while Lucifer is busy",
                "The device shows steady activity but no autonomous planning sessions have run, so the backlog only moves when the user remembers to ask.",
                emptyList(),
                48,
                "Enable the heartbeat if continuous planning is wanted; otherwise the workflow runner is the right tool."
            )
        }
        if (hb.enabled && !hb.customDirective) {
            out += opp(
                OpportunityCategory.PROMPT,
                "Heartbeat is running on the default directive",
                "The heartbeat has a generic mandate. A directive written for the user's actual projects produces sharper drafts.",
                emptyList(),
                56,
                "Open the Agents tab and edit heartbeat.md to name the real projects and what 'high value' means here."
            )
        }
        if (hb.cameraConsent && hb.cameraSessionsInWindow == 0 && hb.sessionsInWindow >= 4) {
            out += opp(
                OpportunityCategory.PRIVACY,
                "Camera consent is on but never used",
                "${hb.sessionsInWindow} sessions ran without a camera capture. Keeping the camera allowance open for nothing is avoidable risk.",
                emptyList(),
                66,
                "Turn camera access off in the Heartbeat panel until it is actually needed."
            )
        }
        if (hb.enabled && hb.sessionsInWindow >= 6) {
            val drafts = input.observations.count { it.kind == ObservationKind.HEARTBEAT }
            if (drafts > 0) {
                out += opp(
                    OpportunityCategory.WORKFLOW,
                    "Heartbeat produces drafts nobody promotes",
                    "${hb.sessionsInWindow} sessions in two weeks. If drafts are not becoming tasks, the loop is generating text instead of progress.",
                    emptyList(),
                    62,
                    "Have the General convert the top draft into a workflow task each session instead of starting a new idea."
                )
            }
        }
        return out
    }

    // ── workflows ────────────────────────────────────────────────────

    private fun workflowOpportunities(input: DetectionInput): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val wf = input.workflow
        if (wf.stalledOverDays >= 2) {
            out += opp(
                OpportunityCategory.WORKFLOW,
                "${wf.stalledOverDays} workflow steps have been stuck for days",
                "A stalled step is invisible work: it consumes attention and produces nothing. Stalled plans should be closed or resumed explicitly.",
                emptyList(),
                72,
                "Review the Work tab and either retry with new context or delete the abandoned tasks."
            )
        }
        if (wf.readyToComplete >= 2) {
            out += opp(
                OpportunityCategory.WORKFLOW,
                "${wf.readyToComplete} finished workflows are waiting for sign-off",
                "Work that is done but unclosed makes the backlog lie about what is left.",
                emptyList(),
                58,
                "Complete or hand off the finished tasks so the queue reflects reality."
            )
        }
        if (wf.active >= 3) {
            out += opp(
                OpportunityCategory.WORKFLOW,
                "Too many workflows active at once",
                "${wf.active} tasks are open. Parallel work in a one-step-at-a-time runner mostly means none of them finish.",
                emptyList(),
                60,
                "Pick one active task and pause the rest."
            )
        }
        return out
    }

    // ── cost / schedule ──────────────────────────────────────────────

    private fun costOpportunities(input: DetectionInput, recent: List<Observation>): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val calls = recent.count { it.kind == ObservationKind.MODEL }
        val cap = input.budget.maxModelCallsPerDay
        if (input.modelCallsToday >= cap * 8 / 10) {
            out += opp(
                OpportunityCategory.COST,
                "Model usage is close to the daily cap",
                "${input.modelCallsToday} of $cap calls used today. Background work plus chat can exhaust the budget and start failing mid-task.",
                emptyList(),
                80,
                "Raise the cap deliberately, or schedule background sessions outside active hours."
            )
        }
        if (calls >= 40) {
            out += opp(
                OpportunityCategory.COST,
                "Heavy model usage in the last two weeks",
                "$calls model calls recorded. Some of them may be work local reasoning or the embedded corpus could have answered.",
                emptyList(),
                50,
                "Route simple status questions to local answers first and reserve the model for judgement calls."
            )
        }
        return out
    }

    // ── device hygiene ───────────────────────────────────────────────

    private fun workflowHygiene(input: DetectionInput, recent: List<Observation>): List<Opportunity> {
        val out = mutableListOf<Opportunity>()
        val appEvents = recent.filter { it.kind == ObservationKind.APP_EVENT }
        if (appEvents.size >= 8) {
            out += opp(
                OpportunityCategory.WORKFLOW,
                "Lots of app installs and removals",
                "${appEvents.size} package changes in two weeks. Lucifer can keep the inventory and permissions picture current without the user remembering each change.",
                appEvents.takeLast(3).map { it.id },
                46,
                "Run the Pixel system audit workflow once a month, or let the General summarize package churn weekly."
            )
        }
        val promptEdits = recent.count { it.kind == ObservationKind.PROMPT_EDIT }
        if (promptEdits >= 5) {
            out += opp(
                OpportunityCategory.PROMPT,
                "Prompt files are being edited often",
                "$promptEdits edits in two weeks suggests the behavior is being tuned ad hoc. Consolidating intent into one file beats scattering instructions.",
                emptyList(),
                50,
                "Review soul.md, heartbeat.md and the agent classes together and remove duplicated or conflicting instructions."
            )
        }
        return out
    }

    /**
     * Memory-server friction is the one place a new MCP tool is the right answer: retrieval keeps
     * missing, so the tool that would fix it is worth proposing (never installing).
     */
    private fun memoryToolOpportunity(input: DetectionInput, recent: List<Observation>): List<Opportunity> {
        val mentions = recent.filter { observation ->
            val text = (observation.summary + " " + observation.detail).lowercase()
            "mcp" in text || "drive memory" in text || "retrieval" in text || "embedding" in text
        }
        if (mentions.size < 2) return emptyList()
        return listOf(
            opp(
                OpportunityCategory.MCP_TOOL,
                "Add a targeted read-only tool to the Drive memory server",
                "${mentions.size} recent events mention memory retrieval friction. A narrower lookup tool (file or heading scoped) is cheaper than widening retrieval everywhere.",
                mentions.takeLast(3).map { it.id },
                64,
                "Draft a read-only tool spec, review it, and add it to the existing Termux memory server yourself."
            )
        )
    }

    private fun backlogOpportunity(input: DetectionInput): Opportunity? {
        if (input.proposalsPending < 8) return null
        return opp(
            OpportunityCategory.UX,
            "${input.proposalsPending} proposals are waiting for review",
            "An inbox that never drains becomes noise, and ignored proposals teach nothing about what actually helps.",
            emptyList(),
            44,
            "Approve, reject, or expire the backlog; keep at most five open proposals."
        )
    }

    private fun opp(
        category: OpportunityCategory,
        title: String,
        why: String,
        evidence: List<String>,
        score: Int,
        action: String
    ): Opportunity = Opportunity(
        id = "",
        category = category,
        title = title.take(140),
        why = why.take(600),
        evidence = evidence.map { it.take(60) }.take(8),
        action = action.take(600),
        score = (category.baseScore * 3 + score * 7) / 10,
        autonomy = Autonomy.APPROVAL_REQUIRED
    ).withFingerprint()
}
