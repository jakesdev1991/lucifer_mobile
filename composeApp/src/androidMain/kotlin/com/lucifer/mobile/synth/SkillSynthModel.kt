package com.lucifer.mobile.synth

import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.bool
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.str
import com.lucifer.mobile.resilience.stringList

/**
 * The shapes behind the skill synthesizer.
 *
 * Everything here is pure Kotlin with no Android types, because the rules that decide what Lucifer
 * learns are the ones worth testing on a host. `tools/synth-core/spec_check.py` mirrors this file and
 * the policy in [SkillSynth]; the Android layer only decides where the bytes live.
 *
 * A "skill" is not a tool. It is one short sentence about how to behave, derived from a turn that
 * already happened: a preference the user stated, a question he answered wrongly and was corrected on,
 * or a tactic that fixed a failing command. It can change wording, order and approach. It can never
 * grant capability — see [SkillSafety].
 */

enum class SkillKind {
    /** The user told him how to behave: "keep it short", "never guess the port". */
    DIRECTIVE,

    /** A previous answer was wrong and this is what it should have said. */
    CORRECTION,

    /** A tactic that previously fixed a failing command on a specific target. */
    PROCEDURE,

    /** A routing lesson: the same question came twice, so the first answer did not land. */
    ROUTING;

    val label: String
        get() = when (this) {
            DIRECTIVE -> "preference"
            CORRECTION -> "correction"
            PROCEDURE -> "procedure"
            ROUTING -> "routing"
        }
}

enum class SkillStatus {
    /** Seen once. Recorded, ranked, not yet injected. */
    CANDIDATE,

    /** Reinforced, or a directly stated preference. Injected into prompts. */
    ACTIVE,

    /** Contradicted too often, or superseded by a newer directive. Kept as history, never injected. */
    RETIRED,

    /** Asked for something he will not learn. Kept so the refusal is visible instead of silent. */
    BLOCKED
}

/** One learned behaviour. Immutable; the synthesizer returns a new state instead of mutating this. */
data class SynthesizedSkill(
    val id: String,
    val kind: SkillKind,
    val trigger: String,
    val instruction: String,
    val evidence: List<String> = emptyList(),
    /** Times this skill was included in a prompt. Exposure, not quality. */
    val hits: Int = 0,
    /** Turns that used it and were not corrected. */
    val wins: Int = 0,
    /** Turns that used it and were corrected. */
    val losses: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val status: SkillStatus = SkillStatus.CANDIDATE,
    val surface: String = ANY_SURFACE,
    /** Why it is blocked or retired. Empty for live skills. */
    val note: String = ""
) {
    /**
     * Laplace-smoothed, so an unused skill sits at 0.5 instead of 1.0. Two wins from zero losses is
     * 0.75, not certainty — this is the number that decides promotion, so it must not flatter.
     */
    val confidence: Double get() = (wins + 1.0) / (wins + losses + 2.0)

    val isLive: Boolean get() = status == SkillStatus.ACTIVE

    val scoreLine: String get() = "$wins✓/$losses✗"
}

/** Where a skill was learned. ANY means it applies wherever he is talking. */
const val ANY_SURFACE = "ANY"

/** What kind of activity produced an experience. */
enum class ExperienceKind { CHAT, COMMAND, WORKFLOW, HEARTBEAT }

/** One thing that happened. Kept in a bounded ring so consolidation has recent history. */
data class Experience(
    val at: Long,
    val kind: ExperienceKind,
    val surface: String,
    val ok: Boolean,
    val question: String,
    val detail: String = ""
)

/** The turn that is waiting for a verdict — a correction or a compliment has to arrive before it counts. */
data class PendingTurn(
    val question: String,
    val answer: String,
    val surface: String,
    val at: Long,
    /** Skills that were in the prompt for this turn; the ones scored when a verdict arrives. */
    val skillIds: List<String> = emptyList()
)

/** Everything the synthesizer knows. Serialised whole; small by construction. */
data class SynthState(
    val version: Int = SYNTH_VERSION,
    val skills: List<SynthesizedSkill> = emptyList(),
    val pending: PendingTurn? = null,
    val experiences: List<Experience> = emptyList(),
    /** Count of requests he refused to learn from. Shown, never injected. */
    val refused: Int = 0,
    val updatedAt: Long = 0L
) {
    fun byId(id: String): SynthesizedSkill? = skills.firstOrNull { it.id == id }

    fun live(): List<SynthesizedSkill> = skills.filter { it.status == SkillStatus.ACTIVE }
}

const val SYNTH_VERSION = 1

object SynthLimits {
    /** Hard cap on stored skills. Consolidation evicts the weakest when this is reached. */
    const val MAX_SKILLS = 64

    /** Examples kept per skill; enough to show why it exists, few enough to stay readable. */
    const val MAX_EVIDENCE = 3

    const val MAX_INSTRUCTION_CHARS = 220
    const val MAX_TRIGGER_CHARS = 80
    const val MAX_QUESTION_CHARS = 160
    const val MAX_ANSWER_CHARS = 400

    /** Prompt budget. Five lines is enough to change behaviour; more is a wall of rules. */
    const val MAX_PROMPT_SKILLS = 5
    const val MAX_PROMPT_CHARS = 1_200

    const val EXPERIENCE_RING = 200

    /** A verdict only applies to a turn this recent. Older than this, it is a new topic. */
    const val PENDING_TTL_MS = 30L * 60L * 1_000L

    /** Two questions this close together are "the same question asked again". */
    const val REPEAT_WINDOW_MS = 10L * 60L * 1_000L

    /** Ranking half-life: a skill that has not been touched in a month loses half its weight. */
    const val HALF_LIFE_MS = 30L * 24L * 60L * 60L * 1_000L

    /** Promotion needs this many corroborating events (or wins). */
    const val PROMOTE_AT = 2

    /** Retirement needs this many losses and a confidence that has actually fallen. */
    const val RETIRE_LOSSES = 3
    const val RETIRE_BELOW = 0.45
}

/** Stable identifier: same kind + same normalised trigger is the same skill. Never String.hashCode. */
object SkillIds {
    fun of(kind: SkillKind, trigger: String): String {
        val normalized = Text.normalize(trigger).take(SynthLimits.MAX_TRIGGER_CHARS)
        return kind.name.lowercase() + "-" + fnv1a(normalized)
    }

    /**
     * FNV-1a over the normalised ASCII text. Deliberately not `hashCode()`: the Python mirror has to
     * produce the same id, and a platform hash would drift the moment either side changed.
     */
    private fun fnv1a(text: String): String {
        var hash = 0x811c9dc5L
        for (char in text) {
            hash = hash xor (char.code.toLong() and 0xFFL)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash.toString(16).padStart(8, '0')
    }
}

/** Text handling shared by every part of the synthesizer. ASCII-normalised on purpose. */
object Text {
    private val nonAlpha = Regex("[^a-z0-9]+")

    fun normalize(raw: String): String =
        raw.lowercase().replace(nonAlpha, " ").trim().replace(Regex(" +"), " ")

    fun clamp(raw: String, max: Int): String {
        val clean = raw.trim()
        if (clean.length <= max) return clean
        val cut = clean.take(max)
        val boundary = cut.lastIndexOf(' ')
        return (if (boundary > max / 2) cut.take(boundary) else cut).trimEnd() + "…"
    }

    /** Content words of a question, in order, without the words every question shares. */
    fun signature(question: String, maxTokens: Int = 6): String {
        val tokens = normalize(question).split(' ').filter { it.isNotEmpty() && it !in STOPWORDS }
        if (tokens.isEmpty()) return normalize(question).take(SynthLimits.MAX_TRIGGER_CHARS)
        return tokens.take(maxTokens).joinToString(" ").take(SynthLimits.MAX_TRIGGER_CHARS)
    }

    fun tokens(text: String): Set<String> =
        normalize(text).split(' ').filter { it.length >= 3 && it !in STOPWORDS }.toSet()

    /** Jaccard overlap of content words. Used for "did he ask the same thing again". */
    fun similarity(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) {
            return if (normalize(a) == normalize(b) && normalize(a).isNotEmpty()) 1.0 else 0.0
        }
        val shared = left.count { it in right }
        val union = left.size + right.size - shared
        return if (union == 0) 0.0 else shared.toDouble() / union
    }

    fun sameQuestion(a: String, b: String): Boolean =
        normalize(a).isNotEmpty() && (normalize(a) == normalize(b) || similarity(a, b) >= 0.8)

    val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was",
        "were", "be", "been", "it", "its", "this", "that", "these", "those", "i", "me", "my", "you",
        "your", "he", "his", "she", "her", "we", "our", "they", "them", "do", "does", "did", "can",
        "could", "should", "would", "will", "shall", "have", "has", "had", "what", "which", "who",
        "when", "where", "why", "how", "please", "lucifer", "just", "now", "then", "there", "here",
        "about", "from", "at", "by", "as", "if", "so", "not", "no", "yes", "up", "out", "but", "any"
    )
}

/**
 * Serialisation is versioned and fails closed: a state file from a newer version is ignored rather
 * than half-read, and a malformed record is dropped instead of corrupting the list.
 */
object SkillSynthCodec {

    fun encode(state: SynthState): String = JsonLite.stringify(
        linkedMapOf(
            "version" to state.version,
            "updatedAt" to state.updatedAt,
            "refused" to state.refused,
            "skills" to state.skills.map(::encodeSkill),
            "experiences" to state.experiences.takeLast(SynthLimits.EXPERIENCE_RING).map(::encodeExperience),
            "pending" to state.pending?.let(::encodePending)
        )
    )

    fun decode(raw: String?): SynthState {
        val root = JsonLite.parseObject(raw) ?: return SynthState()
        val version = root.int("version", 0)
        if (version <= 0 || version > SYNTH_VERSION) return SynthState()
        val skills = (root.arr("skills") ?: emptyList())
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { decodeSkill(it.asStringMap()) }
            .take(SynthLimits.MAX_SKILLS)
        val experiences = (root.arr("experiences") ?: emptyList())
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { decodeExperience(it.asStringMap()) }
        return SynthState(
            version = version,
            skills = skills,
            pending = (root["pending"] as? Map<*, *>)?.let { decodePending(it.asStringMap()) },
            experiences = experiences,
            refused = root.int("refused", 0),
            updatedAt = root.long("updatedAt", 0L)
        )
    }

    private fun Map<*, *>.asStringMap(): Map<String, Any?> =
        entries.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }.toMap()

    private fun encodeSkill(skill: SynthesizedSkill): Map<String, Any?> = linkedMapOf(
        "id" to skill.id,
        "kind" to skill.kind.name,
        "trigger" to skill.trigger,
        "instruction" to skill.instruction,
        "evidence" to skill.evidence,
        "hits" to skill.hits,
        "wins" to skill.wins,
        "losses" to skill.losses,
        "createdAt" to skill.createdAt,
        "updatedAt" to skill.updatedAt,
        "status" to skill.status.name,
        "surface" to skill.surface,
        "note" to skill.note
    )

    private fun encodeExperience(experience: Experience): Map<String, Any?> = linkedMapOf(
        "at" to experience.at,
        "kind" to experience.kind.name,
        "surface" to experience.surface,
        "ok" to experience.ok,
        "question" to experience.question,
        "detail" to experience.detail
    )

    private fun encodePending(pending: PendingTurn): Map<String, Any?> = linkedMapOf(
        "question" to pending.question,
        "answer" to pending.answer,
        "surface" to pending.surface,
        "at" to pending.at,
        "skillIds" to pending.skillIds
    )

    private fun decodeSkill(map: Map<String, Any?>): SynthesizedSkill? {
        val id = map.str("id")
        val instruction = map.str("instruction")
        if (id.isBlank() || instruction.isBlank()) return null
        val kind = runCatching { SkillKind.valueOf(map.str("kind")) }.getOrNull() ?: return null
        val status = runCatching { SkillStatus.valueOf(map.str("status")) }.getOrNull()
            ?: SkillStatus.CANDIDATE
        return SynthesizedSkill(
            id = id,
            kind = kind,
            trigger = map.str("trigger").take(SynthLimits.MAX_TRIGGER_CHARS),
            instruction = instruction.take(SynthLimits.MAX_INSTRUCTION_CHARS),
            evidence = map.stringList("evidence").take(SynthLimits.MAX_EVIDENCE),
            hits = map.int("hits"),
            wins = map.int("wins"),
            losses = map.int("losses"),
            createdAt = map.long("createdAt"),
            updatedAt = map.long("updatedAt"),
            status = status,
            surface = map.str("surface", ANY_SURFACE).ifBlank { ANY_SURFACE },
            note = map.str("note")
        )
    }

    private fun decodeExperience(map: Map<String, Any?>): Experience? {
        val kind = runCatching { ExperienceKind.valueOf(map.str("kind")) }.getOrNull() ?: return null
        return Experience(
            at = map.long("at"),
            kind = kind,
            surface = map.str("surface", ANY_SURFACE).ifBlank { ANY_SURFACE },
            ok = map.bool("ok"),
            question = map.str("question").take(SynthLimits.MAX_QUESTION_CHARS),
            detail = map.str("detail").take(SynthLimits.MAX_ANSWER_CHARS)
        )
    }

    private fun decodePending(map: Map<String, Any?>): PendingTurn? {
        val question = map.str("question")
        if (question.isBlank()) return null
        return PendingTurn(
            question = question.take(SynthLimits.MAX_QUESTION_CHARS),
            answer = map.str("answer").take(SynthLimits.MAX_ANSWER_CHARS),
            surface = map.str("surface", ANY_SURFACE).ifBlank { ANY_SURFACE },
            at = map.long("at"),
            skillIds = map.stringList("skillIds")
        )
    }
}
