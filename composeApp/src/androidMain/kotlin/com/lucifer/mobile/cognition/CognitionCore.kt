package com.lucifer.mobile.cognition

/**
 * The scaffold a model needs before it can be said to *think* about something rather than answer it.
 *
 * Today Lucifer's reasoning is one fixed council: CONQUEST plans, WAR attacks, FAMINE synthesises.
 * Three model calls, no tools, no steps, no check on the result. That structure produces sentences
 * *about* reasoning without doing any. This package adds the missing machinery:
 *
 *   [WorkingMemory]  what is known so far, typed and bounded, compacted instead of truncated at random
 *   [ToolSpec]       what he can *do* mid-thought, with the side effect declared up front
 *   [LoopGuard]      when to stop: steps, wall clock, and the two ways a loop actually wastes time
 *   [PlanParser]     the wire format that turns prose into a decision the runner can act on
 *   [AnswerAudit]    the check that happens *before* the answer is shown — grounded? overclaimed?
 *   [CognitionTriage] whether this question deserves any of that, or a plain reply
 *
 * Everything here is pure Kotlin: no Android, no network, no coroutines driver. `tools/cognition-core/`
 * executes the same rules in Python on every push, and `CognitionCoreTests.kt` runs them for real when
 * a JDK is present. The Android driver lives in `CognitionRunner.kt`.
 *
 * The design rule throughout: **a claim about the device needs an observation from the device.** The
 * model may reason freely about the world, but "your phone is doing X" is only allowed to come from a
 * tool result, because it is the one class of statement the user cannot check by reading.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Tools
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What calling a tool actually does to the world. Declared per tool, not inferred: the runner and the
 * confirmation sheet both read this, and a tool whose effect is mislabelled is the bug that matters.
 */
enum class SideEffect(val label: String) {
    /** Reads Lucifer's own corpus. No side effect anywhere. */
    NONE("read-only"),

    /** Writes to app-private storage — memory, a draft, a ledger. Reversible, local. */
    LOCAL_WRITE("writes local state"),

    /** Reads the device: package list, build facts, network state. Visible to the OS. */
    DEVICE_READ("reads the device"),

    /** Changes the device or another app. Never happens without a person. */
    DEVICE_CHANGE("changes the device"),

    /** Fetches a remote page through the egress gate. Appears in the ledger. */
    NETWORK_READ("fetches a page"),

    /** Sends something outward: a message, a post, a transaction. Never happens without a person. */
    EXTERNAL_SEND("sends something outward");

    val needsConfirmation: Boolean
        get() = this == DEVICE_CHANGE || this == EXTERNAL_SEND || this == LOCAL_WRITE
}

/** One capability the loop may use, described well enough for the model to choose it and for a person to judge it. */
data class ToolSpec(
    val id: String,
    val title: String,
    val effect: SideEffect,
    val argumentHint: String,
    val maxArgumentChars: Int = 400
) {
    val needsConfirmation: Boolean get() = effect.needsConfirmation
}

/** A request to use a tool. Only ever built by [PlanParser] from a parsed plan, never from user text. */
data class ToolCall(val tool: String, val argument: String) {
    fun render(): String = "$tool($argument)"
}

/** What a tool said. [ok] false is not an exception: a failed tool is a fact the loop can reason about. */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val detail: String = ""
) {
    companion object {
        fun refused(reason: String) = ToolResult(false, "", reason)
        fun failed(reason: String) = ToolResult(false, "", reason)
        fun ok(output: String, detail: String = "") = ToolResult(true, output, detail)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Working memory
// ─────────────────────────────────────────────────────────────────────────────

/** What a memory entry is. The kind decides whether compaction is allowed to drop it. */
enum class EntryKind(val priority: Int) {
    /** The question being answered. Never dropped. */
    GOAL(0),
    /** A limit the answer must respect: "no root", "no Play Services". Never dropped. */
    CONSTRAINT(0),
    /** A choice already made. Never dropped. */
    DECISION(1),
    /** Something still unknown, worth resolving. Dropped last. */
    QUESTION(2),
    /** Established knowledge, with its source. Dropped late. */
    FACT(3),
    /** The loop's own judgement about its progress. Dropped before facts. */
    REFLECTION(4),
    /** A tool that was run. Kept for the trace, droppable. */
    ACTION(5),
    /** What a tool returned. The first thing to go when space runs out. */
    OBSERVATION(6)
}

data class Entry(
    val kind: EntryKind,
    val text: String,
    val source: String = "",
    val at: Long = 0L
)

/**
 * The scratchpad, bounded.
 *
 * A loop that appends everything it sees runs out of context on step four and starts forgetting the
 * *goal*, which is the failure mode that makes agents look stupid. So the goal and constraints are
 * pinned, observations are the first thing dropped, and the renderer spends its budget in priority
 * order rather than in arrival order.
 */
class WorkingMemory(
    private val capacity: Int = 64,
    val charBudget: Int = 6_000
) {
    private val entries = mutableListOf<Entry>()

    init {
        require(capacity >= 8) { "capacity must leave room for a goal and its constraints" }
        require(charBudget >= 500) { "charBudget must fit at least the goal" }
    }

    fun append(kind: EntryKind, text: String, source: String = "", at: Long = 0L): WorkingMemory {
        val clean = text.trim()
        if (clean.isEmpty()) return this
        entries += Entry(kind, clean, source, at)
        compact()
        return this
    }

    fun appendAll(kind: EntryKind, texts: List<String>, source: String, at: Long = 0L): WorkingMemory {
        texts.forEach { append(kind, it, source, at) }
        return this
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun of(kind: EntryKind): List<Entry> = entries.filter { it.kind == kind }

    fun count(kind: EntryKind): Int = entries.count { it.kind == kind }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun size(): Int = entries.size

    /**
     * Drop the least useful entry that is allowed to be dropped.
     *
     * Order is deliberate: an observation is cheaper to repeat than a fact is to rediscover, and a
     * reflection that is stale misleads. Pinned kinds (priority 0) are never candidates; if only pinned
     * entries remain, capacity is respected by refusing the append instead of evicting the goal.
     */
    private fun compact() {
        while (entries.size > capacity) {
            // Least useful first: highest priority number wins the eviction, and among equals the OLDEST
            // goes. Keeping the newest observation matters because the loop just acted on it.
            val victim = entries
                .withIndex()
                .filter { (_, entry) -> entry.kind.priority > 0 }
                .maxWithOrNull(compareBy({ it.value.kind.priority }, { -it.index }))
                ?.value
                ?: entries.removeAt(entries.size - 1) // all pinned: drop the newest, never the goal
            entries.remove(victim)
        }
    }

    /**
     * Render for a prompt, newest and most important first, inside [charBudget].
     *
     * Pinned kinds are rendered first and are not truncated. Observations are rendered newest-first and
     * stop at the budget, so a long tool output cannot push the question out of the window.
     */
    fun render(charBudget: Int = this.charBudget): String {
        val builder = StringBuilder()
        val ordered = entries
            .withIndex()
            .sortedWith(compareBy({ it.value.kind.priority }, { -it.index }))
            .map { it.value }
        for (entry in ordered) {
            val line = "- [${entry.kind.name}] ${entry.text}" + if (entry.source.isNotBlank()) " (${entry.source})" else ""
            if (builder.length + line.length + 1 > charBudget && entry.kind.priority > 0) break
            builder.append(line).append('\n')
        }
        return builder.toString().trim()
    }

    /** A one-line summary for a status surface: how much he is holding, and of what. */
    fun summary(): String {
        val parts = EntryKind.values()
            .mapNotNull { kind -> count(kind).takeIf { it > 0 }?.let { "${it} ${kind.name.lowercase()}" } }
        return if (parts.isEmpty()) "working memory empty" else parts.joinToString(" · ")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The loop's stopping rules
// ─────────────────────────────────────────────────────────────────────────────

data class LoopBudget(
    val maxSteps: Int = 8,
    val maxElapsedMs: Long = 90_000,
    /** How many times the identical call may be repeated before it counts as no progress. */
    val maxRepeats: Int = 2,
    /** Total characters of observation the loop will hold. */
    val maxObservationChars: Int = 24_000
)

enum class Termination(val line: String) {
    ANSWERED("answered"),
    STEPS_EXHAUSTED("out of steps"),
    TIME_EXHAUSTED("out of time"),
    STALLED("no progress"),
    OBSERVATION_LIMIT("observation budget spent"),
    TOOL_REFUSED("a tool needed a person"),
    CANCELLED("cancelled")
}

/** Why the loop stopped, in words that can be shown as the reason an answer is thin. */
data class StopReason(val termination: Termination, val detail: String)

/**
 * The three ways a bounded loop goes wrong, checked as pure functions so they can be tested without a
 * model: it runs forever, it spends the whole budget retrying one call, or it fills its memory with
 * repeated identical output. None of these need a heuristic about content — repetition is enough.
 */
object LoopGuard {

    /**
     * @param stepIndex how many steps have already run.
     * @param elapsedMs wall clock since the loop began.
     * @param recentCalls the calls made, oldest first.
     * @param observations the observation texts, oldest first.
     */
    fun stopReason(
        stepIndex: Int,
        elapsedMs: Long,
        budget: LoopBudget,
        recentCalls: List<ToolCall> = emptyList(),
        observations: List<String> = emptyList(),
        observedChars: Int = 0
    ): StopReason? {
        if (elapsedMs >= budget.maxElapsedMs) {
            return StopReason(
                Termination.TIME_EXHAUSTED,
                "the ${budget.maxElapsedMs / 1000}-second budget ran out after $stepIndex steps"
            )
        }
        if (stepIndex >= budget.maxSteps) {
            return StopReason(Termination.STEPS_EXHAUSTED, "used all ${budget.maxSteps} steps without concluding")
        }
        if (observedChars >= budget.maxObservationChars) {
            return StopReason(
                Termination.OBSERVATION_LIMIT,
                "tools returned ${budget.maxObservationChars} characters; further results would evict the question"
            )
        }
        val last = recentCalls.lastOrNull() ?: return null
        val repeats = recentCalls.takeLast(budget.maxRepeats + 1).count { it == last }
        if (repeats > budget.maxRepeats) {
            return StopReason(
                Termination.STALLED,
                "called ${last.render()} $repeats times with the same argument"
            )
        }
        if (observations.size >= 2) {
            val tail = observations.takeLast(2)
            if (tail[0] == tail[1] && tail[0].isNotBlank()) {
                return StopReason(Termination.STALLED, "the last two tool results were identical")
            }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The wire format
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A parsed step. Exactly one of [call] and [answer] is non-null when the model behaved; both null means
 * it produced prose with no marker, which is treated as an answer and flagged so the trace can say so.
 */
data class ParsedStep(
    val thought: String = "",
    val call: ToolCall? = null,
    val answer: String? = null,
    val questions: List<String> = emptyList(),
    val unstructured: Boolean = false
)

/**
 * Parses the plan format the runner asks the model for:
 *
 * ```
 * THOUGHT: the corpus may define entropy in a way this question contradicts.
 * TOOL: corpus_search("entropy and the arrow of time")
 * ```
 * or
 * ```
 * THOUGHT: two observations agree; nothing else can be checked from here.
 * ANSWER: …
 * ```
 *
 * Deliberately strict about tool names: an invented tool is refused with a sentence rather than
 * executed. Deliberately lenient about everything else: a model that writes `ANSWER –` instead of
 * `ANSWER:` still gets its answer through, because failing a turn over punctuation is worse than
 * accepting it.
 */
object PlanParser {

    private val TOOL_LINE = Regex("""(?im)^\s*(TOOL|ACTION|CALL)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)\s*$""")
    private val ANSWER_LINE = Regex("""(?im)^\s*(ANSWER|FINAL|RESPONSE)\s*[:\-–]\s*""")
    private val THOUGHT_LINE = Regex("""(?im)^\s*(THOUGHT|REASONING|THINKING)\s*:\s*""")
    private val QUESTION_LINE = Regex("""(?im)^\s*QUESTION\s*:\s*(.+)$""")

    /** Tools whose results are allowed to ground a claim. Used by [AnswerAudit]; declared here once. */
    fun parse(raw: String, tools: List<ToolSpec>): ParsedStep {
        val text = raw.trim()
        if (text.isEmpty()) return ParsedStep(unstructured = true)

        val known = tools.associateBy { it.id.lowercase() }
        val toolMatch = TOOL_LINE.find(text)
        val answerMatch = ANSWER_LINE.find(text)

        // ORDER DECIDES. If the model wrote its ANSWER first, a `TOOL:` line later in that answer body
        // is part of the answer — prose about a tool, or a code block it is describing — and must not be
        // executed. A model that puts TOOL first still gets its tool.
        if (answerMatch != null && (toolMatch == null || answerMatch.range.first < toolMatch.range.first)) {
            val answer = text.substring(answerMatch.range.last + 1).trim()
            return ParsedStep(
                thought = thoughtBefore(text, answerMatch.range.first),
                answer = answer.ifBlank { null },
                questions = questionsIn(text)
            )
        }

        if (toolMatch != null) {
            val name = toolMatch.groupValues[2].lowercase()
            val argument = unquote(toolMatch.groupValues[3])
            val spec = known[name]
            val thought = thoughtBefore(text, toolMatch.range.first)
            if (spec == null) {
                // An unknown tool is not executed and not silently dropped: it becomes a refusal the
                // loop can react to, which is also what keeps a hallucinated `rm_rf()` harmless.
                return ParsedStep(
                    thought = thought,
                    call = ToolCall("__unknown__", "${toolMatch.groupValues[2]}($argument)"),
                    questions = questionsIn(text),
                    unstructured = true
                )
            }
            val bounded = argument.take(spec.maxArgumentChars)
            return ParsedStep(thought = thought, call = ToolCall(spec.id, bounded), questions = questionsIn(text))
        }

        return ParsedStep(thought = "", answer = text, questions = questionsIn(text), unstructured = true)
    }

    private fun thoughtBefore(text: String, endExclusive: Int): String {
        val head = text.substring(0, endExclusive.coerceIn(0, text.length))
        val match = THOUGHT_LINE.find(head) ?: return ""
        return head.substring(match.range.last + 1).trim()
    }

    private fun questionsIn(text: String): List<String> =
        QUESTION_LINE.findAll(text).map { it.groupValues[1].trim() }.take(4).toList()

    private fun unquote(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.first()
            val last = trimmed.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length - 1).trim()
            }
        }
        return trimmed
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Triage: does this deserve the loop at all?
// ─────────────────────────────────────────────────────────────────────────────

enum class Depth { SHALLOW, DEEP }

data class Triage(val depth: Depth, val reason: String) {
    val deep: Boolean get() = depth == Depth.DEEP
}

/**
 * Not every message deserves eight steps and four model calls.
 *
 * A greeting answered by a loop costs a minute of battery and reads worse. A question that needs the
 * corpus, or that asks for a plan with constraints, is badly served by a single pass. This is the
 * classifier between them, and it is deliberately easy to read: length, clause count, and a small set
 * of shapes that mean "this needs work". It errs shallow, because a shallow answer can be followed up
 * and a slow one cannot be taken back.
 */
object CognitionTriage {

    /**
     * The floor for a question that has no reasoning shape at all. A shaped question ("design",
     * "debug", "why does") clears a lower bar, because length is a poor proxy for difficulty: an
     * 88-character debugging question with a second clause needs the loop; a 400-character rant does not.
     */
    const val MIN_DEEP_CHARS = 60

    private val DEEP_SHAPES = listOf(
        Regex("""(?i)\b(plan|steps|how (do|would|should) (i|we|you)|compare|trade-?off|why does|why is|why would)\b"""),
        Regex("""(?i)\b(design|architect|implement|debug|diagnose|root cause|migrate|audit)\b"""),
        Regex("""(?i)\b(instead of|considering|given that|assuming|if .{10,}? then)\b""")
    )

    private val SHALLOW_OPENERS = listOf(
        "hi", "hello", "hey", "yo", "thanks", "thank you", "good morning", "good night",
        "yes", "no", "ok", "okay", "cool", "nice"
    )

    fun assess(input: String, toolCount: Int = 4): Triage {
        val text = input.trim()
        if (text.isEmpty()) return Triage(Depth.SHALLOW, "nothing to answer")
        val lower = text.lowercase()
        if (SHALLOW_OPENERS.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") && text.length < 24 }) {
            return Triage(Depth.SHALLOW, "a greeting or acknowledgement")
        }
        if (toolCount <= 0) {
            return Triage(Depth.SHALLOW, "no tools available, so extra steps would only cost time")
        }
        if (text.length < MIN_DEEP_CHARS) {
            return Triage(Depth.SHALLOW, "short enough to answer in one pass")
        }
        val clauses = text.count { it == '.' || it == '?' || it == ';' || it == '\n' } + 1
        val shape = DEEP_SHAPES.firstOrNull { it.containsMatchIn(text) }
        return when {
            shape != null && clauses >= 2 && text.length >= MIN_DEEP_CHARS ->
                Triage(Depth.DEEP, "asks for a multi-part answer across $clauses clauses")
            text.length >= 320 ->
                Triage(Depth.DEEP, "long enough that one pass will miss part of it")
            shape != null && text.length >= 160 ->
                Triage(Depth.DEEP, "asks for reasoning, not a fact")
            else -> Triage(Depth.SHALLOW, "ordinary question")
        }
    }
}
