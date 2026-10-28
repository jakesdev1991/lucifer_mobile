// Host-side verification harness for Lucifer's agent ecosystem and General/tactician core.
//
// Not part of the Android build. Run with scripts/verify_agents_core.sh.
package com.lucifer.mobile.agents.harness

import com.lucifer.mobile.agents.*
import com.lucifer.mobile.resilience.FailureClass
import com.lucifer.mobile.resilience.LearningLedger
import com.lucifer.mobile.resilience.Lesson
import com.lucifer.mobile.resilience.RecoveryKind
import com.lucifer.mobile.resilience.ResilienceTarget
import com.lucifer.mobile.tactician.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private var passed = 0
private val failures = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  ok  $name")
    } catch (error: Throwable) {
        failures.add("$name: ${error.message ?: error.javaClass.simpleName}")
        println("FAIL  $name -> ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what: expected <$expected> but was <$actual>")
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    val settled = result ?: throw IllegalStateException("test coroutine suspended; fakes must not suspend")
    return settled.getOrThrow()
}

private class FakeThinker(private val reply: String) : Thinker {
    var calls = 0
    var lastUser = ""
    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Double): String {
        calls++
        lastUser = user
        return reply
    }
}

private fun agentFile(
    id: String,
    name: String = id.replace('-', ' '),
    role: String = "custom",
    enabled: Boolean = true,
    author: String = "user",
    triggers: String = "",
    skills: String = "",
    mandate: String = "# Role\n\nDo a specific, verifiable job for the user with evidence."
): String = listOf(
    "---",
    "id: $id",
    "name: $name",
    "role: $role",
    "created_by: $author",
    "enabled: $enabled",
    "version: 1",
    "skills: $skills",
    "targets: LOCAL,TERMUX,VM,SHIZUKU",
    "triggers: $triggers",
    "---",
    mandate
).joinToString("\n")

private fun obs(
    kind: ObservationKind,
    summary: String,
    at: Long = System.currentTimeMillis(),
    severity: Int = 0,
    detail: String = ""
) = Observation.of(kind, summary, detail, at, source = "test", severity = severity)

private fun recoveryFixture(attempts: Int, successes: Int, signature: String, failureClass: String): LearningLedger {
    val ledger = LearningLedger()
    repeat(attempts) { index ->
        ledger.record(
            Lesson(
                signatureKey = signature,
                failureClass = FailureClass.valueOf(failureClass),
                kind = RecoveryKind.entries[index % RecoveryKind.entries.size],
                target = ResilienceTarget.VM,
                tool = "python3",
                succeeded = index < successes,
                note = "attempt $index",
                createdAt = System.currentTimeMillis() - index * 60_000L
            )
        )
    }
    return ledger
}

fun main() {
    println("Lucifer agent ecosystem · verification harness")

    // ── prompt rules ─────────────────────────────────────────────────
    test("slugify always yields a valid id") {
        eq(PromptRules.slugify("  Disk Space Fixer!! "), "disk-space-fixer", "basic")
        eq(PromptRules.slugify(""), "agent", "empty fallback")
        eq(PromptRules.slugify("123"), "agent-123", "leading digit")
        expect(PromptRules.isValidId(PromptRules.slugify("Café résumé")), "accented input: ${PromptRules.slugify("Café résumé")}")
        expect(!PromptRules.isValidId("Bad ID"), "space rejected")
        expect(!PromptRules.isValidId("x"), "too short rejected")
    }

    test("normalize strips NULs, unifies newlines, and caps length") {
        val raw = "a\r\nb\u0000c\r\nd   "
        eq(PromptRules.normalize(raw, PromptKind.SOUL), "a\nbc\nd", "normalized")
        eq(PromptRules.normalize("x".repeat(20_000), PromptKind.SOUL).length, PromptRules.MAX_SOUL_CHARS, "capped")
    }

    test("validation rejects short bodies and obvious credentials") {
        expect(PromptRules.validationError("short", PromptKind.SOUL) != null, "short rejected")
        expect(PromptRules.validationError("A reasonable soul prompt for Lucifer.", PromptKind.SOUL) == null, "valid accepted")
        val withKey = "You are Lucifer. api_key: sk-abcdefghijklmnopqrstuvwxyz012345"
        expect(PromptRules.validationError(withKey, PromptKind.SOUL) != null, "credential rejected")
        val nvapi = "Use nvapi-abcdefghijklmnop for every call, always."
        expect(PromptRules.validationError(nvapi, PromptKind.SOUL) != null, "nvapi rejected")
    }

    test("composeSystemPrompt orders soul then a bounded agent scope") {
        val prompt = PromptRules.composeSystemPrompt("SOUL TEXT", "AGENT MANDATE")
        expect(prompt.startsWith("SOUL TEXT"), "soul first")
        expect(prompt.indexOf("AGENT MANDATE") > prompt.indexOf("SOUL TEXT"), "agent after soul")
        expect(prompt.contains("cannot expand permissions"), "scope note present")
        eq(PromptRules.composeSystemPrompt("   ", null), PromptDefaults.soulFallback, "blank soul falls back")
    }

    test("built-in defaults are valid and parseable") {
        expect(PromptRules.validationError(PromptDefaults.heartbeat, PromptKind.HEARTBEAT) == null, "heartbeat default valid")
        expect(PromptRules.validationError(PromptDefaults.general, PromptKind.AGENT_CLASS) == null, "general default valid")
        val general = AgentClassFile.decode(PromptDefaults.general, "general")
        eq(general.id, PromptDefaults.GENERAL_AGENT_ID, "general id")
        eq(general.role, AgentRole.GENERAL, "general role")
        eq(general.author, Author.SYSTEM, "general author")
        expect(general.isUsable, "general usable: ${general.problem}")
    }

    // ── agent files ──────────────────────────────────────────────────
    test("front matter decodes into a complete pack") {
        val pack = AgentClassFile.decode(
            agentFile("disk-medic", name = "Disk Medic", role = "operator", triggers = "disk, space, storage", skills = "recovery, cleanup"),
            "disk-medic"
        )
        eq(pack.name, "Disk Medic", "name")
        eq(pack.role, AgentRole.OPERATOR, "role")
        eq(pack.triggers, listOf("disk", "space", "storage"), "triggers")
        eq(pack.skills, listOf("recovery", "cleanup"), "skills")
        eq(pack.targets, listOf("LOCAL", "TERMUX", "VM", "SHIZUKU"), "targets")
        expect(pack.isUsable, "usable")
    }

    test("missing front matter still yields a usable pack from the filename") {
        val pack = AgentClassFile.decode("# Notes\n\nDo something useful with observed evidence.", "ad-hoc-helper")
        eq(pack.id, "ad-hoc-helper", "id from filename")
        eq(pack.role, AgentRole.CUSTOM, "role default")
        eq(pack.author, Author.SYSTEM, "author default")
        expect(pack.enabled, "enabled default")
        expect(pack.isUsable, "usable")
    }

    test("broken agent files are reported, not silently accepted") {
        val badId = AgentClassFile.decode(agentFile("Bad Id"), "fallback")
        expect(badId.problem != null || badId.id == "bad-id", "invalid id handled: ${badId.problem}")

        val short = AgentClassFile.decode("---\nid: shorty\n---\ntoo short", "shorty")
        expect(short.problem != null, "short mandate flagged")
        expect(!short.isUsable, "short mandate unusable")

        val secret = AgentClassFile.decode(
            agentFile("leaky", mandate = "# Leaky\n\nUse password: hunter2hunter2 for everything."),
            "leaky"
        )
        expect(secret.problem != null, "credential flagged")
    }

    test("encode/decode round-trips and preserves authorship") {
        val original = AgentClassFile.decode(agentFile("auditor", name = "Auditor", author = "lucifer"), "auditor")
        val round = AgentClassFile.decode(AgentClassFile.encode(original), "auditor")
        eq(round.id, original.id, "id")
        eq(round.name, original.name, "name")
        eq(round.author, Author.LUCIFER, "author")
        eq(round.mandate.trim(), original.mandate.trim(), "mandate")
    }

    test("front-matter lists are clamped") {
        val many = (1..40).joinToString(",") { "trigger$it" }
        val pack = AgentClassFile.decode(agentFile("clamped", triggers = many), "clamped")
        expect(pack.triggers.size <= 12, "trigger cap, was ${pack.triggers.size}")
        expect(pack.triggers.all { it.length <= 40 }, "item cap")
    }

    test("unknown target names are dropped") {
        val raw = agentFile("targets-test").replace("targets: LOCAL,TERMUX,VM,SHIZUKU", "targets: LOCAL,MOON")
        val pack = AgentClassFile.decode(raw, "targets-test")
        eq(pack.targets, listOf("LOCAL"), "only known targets")
    }

    // ── registry ─────────────────────────────────────────────────────
    val roster = AgentRegistry.fromFiles(
        mapOf(
            "general" to PromptDefaults.general,
            "researcher" to agentFile("researcher", name = "Researcher", role = "researcher", triggers = "research, docs, sources"),
            "disk-medic" to agentFile("disk-medic", name = "Disk Medic", role = "operator", triggers = "disk, space", skills = "recovery"),
            "parked" to agentFile("parked", name = "Parked", enabled = false, triggers = "never")
        )
    )

    test("registry exposes usable, enabled, and broken sets") {
        eq(roster.packs.size, 4, "packs")
        eq(roster.enabled.size, 3, "enabled")
        eq(roster.problems.size, 0, "problems")
        expect(roster.general() != null, "general found")
    }

    test("selection matches triggers and names") {
        eq(roster.select("why is my disk full").pack?.id, "disk-medic", "disk routing")
        eq(roster.select("research the latest kotlin docs").pack?.id, "researcher", "research routing")
        expect(roster.select("hello there").pack?.id == PromptDefaults.GENERAL_AGENT_ID, "fallback to general")
    }

    test("explicit selection respects disabled, missing, and broken agents") {
        eq(roster.select("whatever", explicitId = "parked").pack, null, "disabled refused")
        expect(roster.select("whatever", explicitId = "parked").reason.contains("disabled"), "disabled reason")
        eq(roster.select("whatever", explicitId = "ghost").pack, null, "missing refused")
        val broken = AgentRegistry.fromFiles(mapOf("broken" to "---\nid: broken\n---\nno"))
        eq(broken.select("x").pack, null, "broken roster refuses everything")
    }

    test("overlapping agents are detectable") {
        val overlapping = AgentRegistry.fromFiles(
            mapOf(
                "alpha" to agentFile("alpha", triggers = "disk, space, cleanup", skills = "recovery, triage, audit"),
                "beta" to agentFile("beta", triggers = "disk, space", skills = "recovery, triage, audit")
            )
        )
        eq(overlapping.overlappingPairs().size, 1, "pair found")
    }

    test("roster text is bounded and labels broken entries") {
        val text = roster.rosterText(1_000)
        expect(text.contains("general"), "lists general")
        expect(text.length <= 1_000, "bounded")
        val broken = AgentRegistry.fromFiles(mapOf("bad" to "---\nid: bad\n---\nno")).rosterText()
        expect(broken.contains("BROKEN"), "broken labelled: $broken")
    }

    test("registry survives duplicate ids and oversized rosters") {
        val files = (1..40).associate { "agent-$it" to agentFile("agent-$it") }
        val registry = AgentRegistry.fromFiles(files)
        expect(registry.packs.size <= PromptRules.MAX_AGENTS, "capped at ${registry.packs.size}")
        eq(registry.packs.map { it.id }.distinct().size, registry.packs.size, "no duplicate ids")
    }

    // ── composer ─────────────────────────────────────────────────────
    test("composer builds a usable agent without a model") {
        val draft = SkillComposer.compose(
            ComposeRequest(
                goal = "Fix disk pressure on the VM without deleting user data.",
                skills = listOf(SkillRef("disk", "Disk Triage", "find what fills the disk", safetyBoundary = "read-only first")),
                notes = listOf("signature abc123 · 5 attempts · 0 success")
            )
        )
        expect(PromptRules.isValidId(draft.id), "valid id: ${draft.id}")
        eq(draft.author, Author.LUCIFER, "author")
        expect(draft.mandate.contains("Disk Triage"), "skill referenced")
        expect(draft.mandate.contains("Boundaries"), "boundaries kept")
        expect(draft.mandate.contains("non-read-only", ignoreCase = true) || draft.mandate.contains("No autonomous"), "safety text")
        expect(AgentClassFile.decode(draft.toFile(), draft.id).isUsable, "draft file parses")
    }

    test("composer never collides with an existing id") {
        val draft = SkillComposer.compose(
            ComposeRequest(name = "Researcher", goal = "Look things up."),
            existingIds = setOf("researcher", "researcher-2")
        )
        eq(draft.id, "researcher-3", "unique suffix")
    }

    test("model-assisted composition keeps the deterministic guardrails") {
        val reply = """{"name":"Disk Medic","mandate":"# Disk Medic\n\nWatch disk usage and propose cleanup.","triggers":["disk","storage"]}"""
        val thinker = FakeThinker(reply)
        val draft = runSuspend {
            SkillComposer.composeWithModel(
                ComposeRequest(goal = "Handle disk pressure."),
                thinker
            )
        }
        eq(thinker.calls, 1, "one model call")
        eq(draft.origin, "model-assisted", "origin")
        expect(draft.mandate.contains("Non-read-only commands require"), "boundary appended")
        expect(draft.triggers.contains("disk"), "triggers merged: ${draft.triggers}")
        expect(PromptRules.isValidId(draft.id), "id stable")
    }

    test("model composition rejects credential-bearing or empty mandates") {
        val leaky = FakeThinker("""{"name":"Leaky","mandate":"Use password: hunter2hunter2hunter2 to ssh in."}""")
        val draft = runSuspend { SkillComposer.composeWithModel(ComposeRequest(goal = "ssh helper"), leaky) }
        expect(!draft.mandate.contains("hunter2"), "secret not adopted")

        val empty = FakeThinker("""{"name":"Empty","mandate":""}""")
        val second = runSuspend { SkillComposer.composeWithModel(ComposeRequest(goal = "do a thing properly"), empty) }
        expect(second.mandate.length > PromptRules.MIN_BODY_CHARS, "falls back to template")
    }

    test("nameFromGoal is sane") {
        eq(SkillComposer.nameFromGoal(""), "Specialist", "empty")
        expect(SkillComposer.nameFromGoal("keep the vm disk from filling up").startsWith("Disk Filling"), "words: ${SkillComposer.nameFromGoal("keep the vm disk from filling up")}")
    }

    // ── observations ─────────────────────────────────────────────────
    test("observations redact and truncate") {
        val entry = Observation.of(
            ObservationKind.TERMINAL,
            "ran curl with Authorization: Bearer abcdefghijklmnop",
            detail = "token=supersecretvalue and more text " + "x".repeat(2_000)
        )
        expect(!entry.summary.contains("abcdefghijklmnop"), "summary redacted: ${entry.summary}")
        expect(!entry.detail.contains("supersecretvalue"), "detail redacted")
        expect(entry.detail.length <= Observation.MAX_DETAIL, "detail capped")
        expect(entry.id.startsWith("o"), "id assigned")
    }

    test("observation log caps per kind and dedupes identical entries") {
        val now = 1_000_000L
        val ledger = ObservationLedger(maxTotal = 50, maxPerKind = 5)
        repeat(20) { ledger.add(obs(ObservationKind.TERMINAL, "same failure", at = now + it)) }
        expect(ledger.size <= 5, "per-kind cap, was ${ledger.size}")
        val single = obs(ObservationKind.USER, "hello", at = now)
        ledger.add(single)
        ledger.add(single)
        eq(ledger.ofKind(ObservationKind.USER).size, 1, "deduped by id")
    }

    test("observation windows and counts behave") {
        val now = 10_000_000L
        val ledger = ObservationLedger(listOf(
            obs(ObservationKind.MODEL, "called model", at = now - 60_000),
            obs(ObservationKind.TERMINAL, "failed", at = now - 120_000, severity = 2),
            obs(ObservationKind.TERMINAL, "old", at = now - 10 * 24 * 60 * 60 * 1_000L)
        ))
        eq(ledger.recent(24 * 60 * 60 * 1_000L, now).size, 2, "window")
        eq(ledger.failures(24 * 60 * 60 * 1_000L, now).size, 1, "failures")
        eq(ledger.counts(24 * 60 * 60 * 1_000L, now)[ObservationKind.MODEL], 1, "counts")
    }

    test("observation digest includes ids, counts, and stays bounded") {
        val ledger = ObservationLedger()
        repeat(30) { ledger.add(obs(ObservationKind.TERMINAL, "command failed $it", severity = 2)) }
        val digest = ledger.digest(24 * 60 * 60 * 1_000L, maxChars = 800)
        expect(digest.contains("Failures"), "failures section")
        expect(digest.contains("[o"), "evidence ids")
        expect(digest.length <= 900, "bounded: ${digest.length}")
        expect(ObservationLedger().digest(1_000L).contains("No recorded"), "empty digest")
    }

    test("observation ledger round-trips and rejects unknown schema") {
        val ledger = ObservationLedger()
        ledger.add(obs(ObservationKind.AGENT_RUN, "general ran"))
        val decoded = ObservationLedger.decode(ledger.encode())
        expect(decoded != null, "decoded")
        eq(decoded!!.size, 1, "size")
        eq(ObservationLedger.decode("{\"schema\":42,\"observations\":[]}"), null, "schema")
        eq(ObservationLedger.decode("nope"), null, "garbage")
    }

    test("recovery history is summarized from the real lesson format") {
        val ledger = recoveryFixture(attempts = 4, successes = 1, signature = "abc123", failureClass = "MISSING_DEPENDENCY")
        val digest = RecoveryHistory.summarize(ledger.encode())
        eq(digest.size, 1, "one signature")
        eq(digest.first().attempts, 4, "attempts")
        eq(digest.first().successes, 1, "successes")
        eq(digest.first().failureClass, "MISSING_DEPENDENCY", "class")
        eq(RecoveryHistory.summarize("garbage"), emptyList<RecoveryDigest>(), "garbage safe")
    }

    // ── opportunities ────────────────────────────────────────────────
    fun detection(
        observations: List<Observation> = emptyList(),
        recovery: List<RecoveryDigest> = emptyList(),
        agents: AgentRegistry = roster,
        runs: Map<String, Int> = mapOf("general" to 3),
        workflow: WorkflowSignals = WorkflowSignals(),
        heartbeat: HeartbeatSignals = HeartbeatSignals(),
        callsToday: Int = 0,
        pending: Int = 0,
        now: Long = System.currentTimeMillis(),
        budget: AutonomyBudget = AutonomyBudget(quietStartHour = 0, quietEndHour = 0)
    ) = DetectionInput(
        observations = observations,
        agents = agents,
        recovery = recovery,
        agentRuns = runs,
        workflow = workflow,
        heartbeat = heartbeat,
        modelCallsToday = callsToday,
        proposalsPending = pending,
        budget = budget,
        now = now
    )

    test("exhausted recovery produces the top reliability proposal") {
        val recovery = RecoveryHistory.summarize(
            recoveryFixture(attempts = 5, successes = 0, signature = "deadbeef", failureClass = "MISSING_DEPENDENCY").encode()
        )
        val found = OpportunityDetector.detect(detection(recovery = recovery))
        expect(found.isNotEmpty(), "proposals produced")
        eq(found.first().category, OpportunityCategory.RELIABILITY, "category")
        expect(found.first().evidence.contains("deadbeef"), "evidence cites signature")
        expect(found.first().autonomy == Autonomy.APPROVAL_REQUIRED, "never autonomous")
    }

    test("missing instrumentation is caught") {
        val observations = (1..6).map { obs(ObservationKind.TERMINAL, "failed $it", severity = 2) }
        val found = OpportunityDetector.detect(detection(observations = observations, recovery = emptyList()))
        expect(found.any { it.title.contains("not being captured") }, "instrumentation: ${found.map { it.title }}")
    }

    test("idle and overlapping agents are flagged") {
        val found = OpportunityDetector.detect(
            detection(runs = mapOf("general" to 2))
        )
        expect(found.any { it.category == OpportunityCategory.AGENT }, "agent hygiene flagged: ${found.map { it.title }}")
    }

    test("camera consent without use is flagged as privacy" ) {
        val found = OpportunityDetector.detect(
            detection(heartbeat = HeartbeatSignals(
                enabled = true,
                cameraConsent = true,
                sessionsInWindow = 6,
                cameraSessionsInWindow = 0,
                customDirective = true
            ))
        )
        expect(found.any { it.category == OpportunityCategory.PRIVACY }, "privacy proposal: ${found.map { it.title }}")
    }

    test("default heartbeat directive is flagged when enabled") {
        val found = OpportunityDetector.detect(
            detection(heartbeat = HeartbeatSignals(enabled = true, customDirective = false))
        )
        expect(found.any { it.category == OpportunityCategory.PROMPT }, "prompt proposal")
    }

    test("cost and backlog guards fire") {
        val found = OpportunityDetector.detect(detection(callsToday = 39, pending = 9))
        expect(found.any { it.category == OpportunityCategory.COST }, "cost")
        expect(found.any { it.category == OpportunityCategory.UX }, "backlog")
    }

    test("stalled workflows fire") {
        val found = OpportunityDetector.detect(
            detection(workflow = WorkflowSignals(total = 4, active = 3, readyToComplete = 2, stalledOverDays = 3))
        )
        expect(found.any { it.title.contains("stuck") }, "stalled: ${found.map { it.title }}")
        expect(found.any { it.title.contains("sign-off") }, "ready to complete")
        expect(found.any { it.title.contains("active at once") }, "too many active")
    }

    test("proposal scores stay in range and fingerprints are stable") {
        val recovery = RecoveryHistory.summarize(
            recoveryFixture(attempts = 5, successes = 0, signature = "x1", failureClass = "AUTH").encode()
        )
        val first = OpportunityDetector.detect(detection(recovery = recovery))
        val second = OpportunityDetector.detect(detection(recovery = recovery))
        expect(first.all { it.score in 0..100 }, "score bounds")
        eq(first.map { it.fingerprint }.toSet(), second.map { it.fingerprint }.toSet(), "stable fingerprints")
    }

    // ── proposal ledger ──────────────────────────────────────────────
    test("proposal merge dedupes and does not resurrect rejected items") {
        val ledger = ProposalLedger()
        val first = OpportunityDetector.detect(detection(callsToday = 39))
        val added = ledger.merge(first)
        expect(added.isNotEmpty(), "added")
        eq(ledger.merge(first).size, 0, "second merge adds nothing")
        val target = ledger.pending().first()
        ledger.setStatus(target.fingerprint, ProposalStatus.REJECTED)
        ledger.merge(first)
        eq(ledger.find(target.fingerprint)?.status, ProposalStatus.REJECTED, "rejected stays rejected")
    }

    test("proposal status transitions and counts") {
        val ledger = ProposalLedger()
        ledger.merge(OpportunityDetector.detect(detection(callsToday = 39, pending = 9)))
        val target = ledger.pending().first()
        eq(ledger.setStatus(target.fingerprint, ProposalStatus.APPROVED)?.status, ProposalStatus.APPROVED, "approved")
        eq(ledger.pending().size, ledger.all().size - 1, "pending shrinks")
        eq(ledger.setStatus("missing", ProposalStatus.APPLIED), null, "unknown fingerprint")
    }

    test("proposal ledger round-trips and is bounded") {
        val ledger = ProposalLedger()
        ledger.merge((1..30).map { index ->
            Opportunity(
                id = "",
                category = OpportunityCategory.SKILL,
                title = "proposal $index",
                why = "because",
                evidence = emptyList(),
                action = "do it",
                score = 50
            ).withFingerprint()
        })
        expect(ledger.size <= ProposalLedger.MAX_ENTRIES, "bounded")
        val decoded = ProposalLedger.decode(ledger.encode())
        expect(decoded != null, "decoded")
        eq(decoded!!.size, ledger.size, "round-trip size")
        eq(ProposalLedger.decode("{\"schema\":0}"), null, "bad schema")
    }

    // ── budget ───────────────────────────────────────────────────────
    test("quiet hours wrap midnight and block runs") {
        val budget = AutonomyBudget(quietStartHour = 23, quietEndHour = 7)
        expect(budget.isQuietHour(23, 30), "23:30 quiet")
        expect(budget.isQuietHour(3, 0), "03:00 quiet")
        expect(!budget.isQuietHour(12, 0), "noon active")
        val now = System.currentTimeMillis()
        expect(!budget.allowsRun(now - 5 * 60_000, 0, now, 23, 30).allowed, "quiet blocked")
        expect(!budget.allowsRun(now - 5 * 60_000, 0, now, 12, 0).allowed, "interval blocked")
        expect(budget.allowsRun(now - 60 * 60_000, 0, now, 12, 0).allowed, "allowed")
        expect(!budget.allowsRun(now - 60 * 60_000, budget.maxModelCallsPerDay, now, 12, 0).allowed, "daily cap")
    }

    // ── tactician ────────────────────────────────────────────────────
    test("tactician runs deterministically with no model") {
        val engine = TacticianEngine(null)
        val recovery = RecoveryHistory.summarize(
            recoveryFixture(attempts = 5, successes = 0, signature = "sig-9", failureClass = "NETWORK").encode()
        )
        val run = runSuspend { engine.run(detection(recovery = recovery)) }
        eq(run.report.source, "detector", "source")
        expect(run.report.proposals.isNotEmpty(), "proposals")
        expect(run.report.situation.contains("bottleneck"), "situation written: ${run.report.situation}")
        expect(run.report.notes.any { it.contains("No model") }, "notes mention fallback")
        expect(run.report.toMarkdown().contains("Tactician brief"), "markdown")
        expect(run.report.toMarkdown().contains("proposed") || run.report.toMarkdown().contains("Nothing in this brief was executed"), "safety footer")
    }

    test("tactician merges model insight without dropping detector findings") {
        val reply = """{"situation":"Disk pressure and a stale roster are the real cost.",
            "proposals":[{"category":"MCP_TOOL","title":"Add a disk-usage memory tool",
            "why":"Disk failures recur","action":"Propose the tool, do not build it","evidence":["o123"]}]}"""
        val engine = TacticianEngine(FakeThinker(reply))
        val recovery = RecoveryHistory.summarize(
            recoveryFixture(attempts = 5, successes = 0, signature = "sig-7", failureClass = "DISK_FULL").encode()
        )
        val run = runSuspend { engine.run(detection(recovery = recovery)) }
        eq(run.report.source, "detector+model", "source")
        expect(run.report.situation.contains("Disk pressure"), "model situation used")
        expect(run.report.proposals.size >= 2, "detector + model proposals: ${run.report.proposals.size}")
        expect(run.report.proposals.all { it.autonomy == Autonomy.APPROVAL_REQUIRED }, "all approval gated")
        expect(run.report.proposals.any { it.title.contains("Unresolved") }, "detector finding kept")
    }

    test("tactician respects quiet hours and reports why") {
        val reply = """{"situation":"should not be used","proposals":[]}"""
        val thinker = FakeThinker(reply)
        val engine = TacticianEngine(thinker)
        val quiet = System.currentTimeMillis()
        val run = runSuspend {
            engine.run(detection(now = quiet, budget = AutonomyBudget(quietStartHour = 0, quietEndHour = 23)))
        }
        eq(thinker.calls, 0, "model not called in quiet hours")
        expect(run.report.notes.any { it.contains("skipped") }, "note explains: ${run.report.notes}")
        expect(run.report.budgetReason.contains("quiet"), "budget reason: ${run.report.budgetReason}")
    }

    test("tactician tolerates a garbage model reply") {
        val engine = TacticianEngine(FakeThinker("I cannot do JSON today."))
        val run = runSuspend { engine.run(detection()) }
        eq(run.report.source, "detector", "source stays deterministic")
        expect(run.report.proposals.isNotEmpty() || run.report.notes.isNotEmpty(), "report still useful")
    }

    test("unknown model categories are dropped") {
        val reply = """{"situation":"x","proposals":[{"category":"WIPE_DEVICE","title":"nuke","why":"","action":""}]}"""
        val run = runSuspend { TacticianEngine(FakeThinker(reply)).run(detection()) }
        expect(run.report.proposals.none { it.title == "nuke" }, "unknown category dropped")
    }

    test("helpers expose a usable panel summary") {
        val observations = listOf(
            obs(ObservationKind.MODEL, "call"),
            obs(ObservationKind.TERMINAL, "ok")
        )
        expect(TacticianEngine.summarizeCounts(observations).contains("model call"), "counts summary")
        eq(TacticianEngine.callCountToday(observations, System.currentTimeMillis()), 1, "calls today")
        val ledger = ProposalLedger()
        ledger.merge(OpportunityDetector.detect(detection(callsToday = 39)))
        expect(TacticianEngine.pendingCount(ledger) >= 1, "pending count")
        expect(TacticianEngine.topProposal(ledger) != null, "top proposal")
        expect(TacticianEngine.budgetLine(AutonomyBudget(), 3, 0L, System.currentTimeMillis()).contains("3/"), "budget line")
    }

    // ── memory MCP tool drafts ───────────────────────────────────────
    test("memory tool draft is valid, read-only, and non-executing") {
        val draft = McpToolProposals.build("heading_lookup", "Heading lookup", "Find a heading across the memory corpus.")
        eq(McpToolProposals.problems(draft), emptyList<String>(), "no problems")
        expect(draft.isReadOnly, "read-only")
        val json = draft.toJson()
        expect(json.contains("\"readOnlyHint\":true"), "read-only hint: $json")
        expect(json.contains("\"executed\":false"), "not executed")
        expect(json.contains("drive-memory"), "names the existing server")
        expect(draft.termuxChecklist.isNotEmpty(), "checklist present")
    }

    test("memory tool names are normalized and validated") {
        eq(McpToolProposals.slugFor("Add a Disk Usage Tool!"), "add_a_disk_usage_tool", "slug")
        expect(McpToolProposals.slugFor("!!!").isNotEmpty(), "never empty")
        val normalized = McpToolProposals.build("Bad Name", "x", "y")
        eq(normalized.name, "bad_name", "bad names are normalized at build time")
        val rawBad = McpToolDraft(
            name = "Bad Name",
            title = "x",
            purpose = "y",
            inputSchema = mapOf("type" to "object", "required" to listOf("query")),
            returns = "",
            notes = emptyList(),
            termuxChecklist = emptyList()
        )
        expect(McpToolProposals.problems(rawBad).isNotEmpty(), "invalid name rejected")
        val noQuery = McpToolDraft(
            name = "lookup",
            title = "Lookup",
            purpose = "p",
            inputSchema = mapOf("type" to "object"),
            returns = "",
            notes = emptyList(),
            termuxChecklist = emptyList()
        )
        expect(McpToolProposals.problems(noQuery).any { it.contains("query") }, "query required")
    }

    test("a memory-friction proposal becomes a draft; other categories do not") {
        val observations = listOf(
            obs(ObservationKind.MODEL, "MCP retrieval returned nothing", severity = 0),
            obs(ObservationKind.TERMINAL, "drive memory embedding server unreachable")
        )
        val found = OpportunityDetector.detect(detection(observations = observations))
        val mcp = found.firstOrNull { it.category == OpportunityCategory.MCP_TOOL }
        expect(mcp != null, "mcp proposal: ${found.map { it.title }}")
        val draft = McpToolProposals.from(mcp!!)
        expect(draft != null, "draft built")
        eq(McpToolProposals.problems(draft!!), emptyList<String>(), "valid draft")
        val skill = Opportunity(
            id = "", category = OpportunityCategory.SKILL, title = "lookup", why = "", evidence = emptyList(),
            action = "", score = 10
        ).withFingerprint()
        eq(McpToolProposals.from(skill), null, "non-mcp proposal ignored")
    }

    test("evidence line helper is stable") {
        val empty = Opportunity("", OpportunityCategory.SKILL, "t", "", emptyList(), "", 10).withFingerprint()
        eq(empty.evidenceLine(), "no evidence ids recorded", "empty evidence")
        val withEvidence = empty.copy(evidence = listOf("o1", "o2"))
        eq(withEvidence.evidenceLine(), "o1, o2", "joined evidence")
    }

    println()
    if (failures.isEmpty()) {
        println("PASS · $passed checks")
    } else {
        println("FAILED · $passed passed, ${failures.size} failed")
        failures.forEach { println(" - $it") }
        kotlin.system.exitProcess(1)
    }
}
