package com.lucifer.mobile.resilience

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Lucifer's local learning ledger.
 *
 * Every recovery attempt - successful or not - becomes a [Lesson] keyed by the failure
 * signature, so the same mistake is not repeated and tactics that actually worked bubble up.
 * Ranking uses a recency-weighted Laplace estimate plus a small exploration bonus, which keeps
 * the agent from locking onto a single tactic forever.
 *
 * Pure Kotlin and bounded: at most [MAX_LESSONS] records, notes truncated, JSON written by
 * [JsonLite]. The Android layer decides where the bytes live.
 */
data class Lesson(
    val signatureKey: String,
    val failureClass: FailureClass,
    val kind: RecoveryKind,
    val target: ResilienceTarget,
    val tool: String,
    val succeeded: Boolean,
    val note: String,
    val createdAt: Long,
    val episodeId: String = ""
) {
    val confidence: Double get() = if (succeeded) 1.0 else 0.0
}

/** Aggregate record for one tactic on one failure signature. */
data class StrategyStat(
    val kind: RecoveryKind,
    val attempts: Int,
    val successes: Int,
    val lastUsedAt: Long
) {
    /** Laplace-smoothed success rate; untried tactics sit at 0.5, which is honest uncertainty. */
    val successRate: Double get() = (successes + 1.0) / (attempts + 2.0)
    val isTried: Boolean get() = attempts > 0
}

data class RankedStrategy(val kind: RecoveryKind, val score: Double, val stat: StrategyStat)

data class LessonSummary(
    val signatureKey: String,
    val failureClass: FailureClass?,
    val totalAttempts: Int,
    val successes: Int,
    val best: RankedStrategy?,
    val text: String
)

class LearningLedger(
    initialLessons: List<Lesson> = emptyList(),
    private val maxLessons: Int = MAX_LESSONS,
    private val halfLifeMillis: Double = HALF_LIFE_MILLIS
) {
    private val records = ArrayList<Lesson>(initialLessons.size.coerceAtMost(maxLessons))

    init {
        initialLessons.asSequence()
            .filter { it.signatureKey.isNotBlank() && it.note.length <= MAX_NOTE_CHARS }
            .take(maxLessons)
            .forEach { records.add(it) }
    }

    val size: Int get() = records.size

    fun lessons(): List<Lesson> = records.toList()

    fun lessonsFor(signatureKey: String): List<Lesson> =
        records.filter { it.signatureKey == signatureKey }

    /**
     * Record an outcome. Returns false when the lesson was rejected as malformed, so callers can
     * report storage honestly instead of silently dropping data.
     */
    @Synchronized
    fun record(lesson: Lesson): Boolean {
        if (lesson.signatureKey.isBlank()) return false
        val clean = lesson.copy(
            note = ResilienceRedactor.redact(lesson.note, MAX_NOTE_CHARS),
            tool = lesson.tool.take(32),
            episodeId = lesson.episodeId.take(40)
        )
        records.add(clean)
        trim()
        return true
    }

    @Synchronized
    fun clear() = records.clear()

    /** Remove one failure signature's lessons (user-initiated forget). */
    @Synchronized
    fun forget(signatureKey: String): Int {
        val before = records.size
        records.removeAll { it.signatureKey == signatureKey }
        return before - records.size
    }

    fun statsFor(signatureKey: String): Map<RecoveryKind, StrategyStat> {
        val relevant = records.filter { it.signatureKey == signatureKey }
        if (relevant.isEmpty()) return emptyMap()
        return relevant.groupBy { it.kind }.mapValues { (kind, lessons) ->
            StrategyStat(
                kind = kind,
                attempts = lessons.size,
                successes = lessons.count { it.succeeded },
                lastUsedAt = lessons.maxOf { it.createdAt }
            )
        }
    }

    fun episodeAttempts(episodeId: String): Map<RecoveryKind, Boolean> {
        if (episodeId.isBlank()) return emptyMap()
        val relevant = records.filter { it.episodeId == episodeId }
        if (relevant.isEmpty()) return emptyMap()
        // A tactic counts as "already used" only if it has not since succeeded in this episode.
        val out = LinkedHashMap<RecoveryKind, Boolean>()
        for (lesson in relevant.sortedBy { it.createdAt }) {
            out[lesson.kind] = lesson.succeeded
        }
        return out
    }

    /**
     * Rank tactics for one signature. Higher is better; the score blends
     * recency-weighted success, coverage of attempts, and a small exploration bonus.
     */
    fun rank(signatureKey: String, kinds: List<RecoveryKind>, now: Long = System.currentTimeMillis()): List<RankedStrategy> {
        val stats = statsFor(signatureKey)
        val relevant = records.filter { it.signatureKey == signatureKey }
        return kinds.distinct().map { kind ->
            val stat = stats[kind] ?: StrategyStat(kind, 0, 0, 0L)
            val kindLessons = relevant.filter { it.kind == kind }
            var weightSum = 0.0
            var weightedSuccess = 0.0
            for (lesson in kindLessons) {
                val ageDays = ((now - lesson.createdAt).coerceAtLeast(0L)) / DAY_MILLIS
                val weight = 0.5.pow(ageDays / halfLifeDays())
                weightSum += weight
                if (lesson.succeeded) weightedSuccess += weight
            }
            val recencyRate = (weightedSuccess + 1.0) / (weightSum + 2.0)
            val exploration = EXPLORATION_BONUS / sqrt(1.0 + weightSum)
            // A tactic that was tried and always failed should not be nudged by exploration.
            val penalty = if (stat.attempts > 0 && stat.successes == 0) FAILURE_PENALTY else 0.0
            RankedStrategy(kind, (recencyRate * 0.85 + exploration - penalty).coerceIn(0.0, 1.0), stat)
        }.sortedWith(compareByDescending<RankedStrategy> { it.score }.thenBy { it.kind.ordinal })
    }

    /** The single best-known tactic for a signature, or null when nothing has been learned. */
    fun bestMove(signatureKey: String, now: Long = System.currentTimeMillis()): RankedStrategy? =
        rank(signatureKey, records.filter { it.signatureKey == signatureKey }.map { it.kind }.distinct(), now)
            .firstOrNull { it.stat.isTried }

    fun summary(signatureKey: String, now: Long = System.currentTimeMillis()): LessonSummary {
        val relevant = records.filter { it.signatureKey == signatureKey }
        val best = bestMove(signatureKey, now)
        val text = if (relevant.isEmpty()) {
            "No lessons yet for this failure signature."
        } else {
            val attempts = relevant.size
            val successes = relevant.count { it.succeeded }
            val detail = relevant.groupBy { it.kind }.entries.sortedByDescending { it.value.size }
                .joinToString("; ") { (kind, list) ->
                    "${kind.label}: ${list.count { it.succeeded }}/${list.size}"
                }
            "$attempts attempts, $successes successful. $detail."
        }
        return LessonSummary(
            signatureKey = signatureKey,
            failureClass = relevant.lastOrNull()?.failureClass,
            totalAttempts = relevant.size,
            successes = relevant.count { it.succeeded },
            best = best,
            text = text
        )
    }

    /** Cross-signature standouts: tactics that keep paying off, used in prompts and the panel. */
    fun standouts(now: Long = System.currentTimeMillis(), limit: Int = 5): List<RankedStrategy> {
        val kinds = records.map { it.kind }.distinct()
        if (kinds.isEmpty()) return emptyList()
        return kinds.map { kind ->
            val list = records.filter { it.kind == kind }
            var weightSum = 0.0
            var weightedSuccess = 0.0
            for (lesson in list) {
                val ageDays = ((now - lesson.createdAt).coerceAtLeast(0L)) / DAY_MILLIS
                val weight = 0.5.pow(ageDays / halfLifeDays())
                weightSum += weight
                if (lesson.succeeded) weightedSuccess += weight
            }
            RankedStrategy(
                kind,
                ((weightedSuccess + 1.0) / (weightSum + 2.0)).coerceIn(0.0, 1.0),
                StrategyStat(kind, list.size, list.count { it.succeeded }, list.maxOf { it.createdAt })
            )
        }.sortedWith(compareByDescending<RankedStrategy> { it.score }.thenBy { it.kind.ordinal }).take(limit)
    }

    /** Compact prompt context describing what has actually worked for this failure. */
    fun promptContext(signal: FailureSignal, now: Long = System.currentTimeMillis()): String {
        val relevant = records.filter { it.signatureKey == signal.key }
        val global = standouts(now, 3)
        if (relevant.isEmpty() && global.isEmpty()) return ""
        val builder = StringBuilder("Learned recovery history (local, from real outcomes):\n")
        if (relevant.isNotEmpty()) {
            builder.append("- For this failure: ").append(summary(signal.key, now).text).append('\n')
        }
        if (global.isNotEmpty()) {
            builder.append("- Tactics that work often here: ")
                .append(global.joinToString(", ") { "${it.kind.label} (${it.stat.successes}/${it.stat.attempts})" })
                .append('\n')
        }
        return builder.toString().take(1_200)
    }

    fun encode(): String {
        val array = records.map { lesson ->
            linkedMapOf<String, Any?>(
                "k" to lesson.signatureKey,
                "c" to lesson.failureClass.name,
                "m" to lesson.kind.name,
                "t" to lesson.target.name,
                "x" to lesson.tool,
                "s" to lesson.succeeded,
                "n" to lesson.note,
                "at" to lesson.createdAt,
                "e" to lesson.episodeId
            )
        }
        return JsonLite.stringify(
            linkedMapOf(
                "schema" to SCHEMA_VERSION,
                "lessons" to array
            )
        )
    }

    private fun trim() {
        while (records.size > maxLessons) records.removeAt(0)
        // Keep one signature from monopolising the ledger during a long recovery loop.
        val perSignature = records.groupBy { it.signatureKey }.filterValues { it.size > MAX_PER_SIGNATURE }
        if (perSignature.isNotEmpty()) {
            val victims = perSignature.flatMap { (_, list) -> list.take(list.size - MAX_PER_SIGNATURE) }
            records.removeAll(victims.toSet())
        }
    }

    private fun halfLifeDays(): Double = (halfLifeMillis / DAY_MILLIS).coerceAtLeast(1.0)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_LESSONS = 400
        const val MAX_PER_SIGNATURE = 40
        const val MAX_NOTE_CHARS = 220
        private const val EXPLORATION_BONUS = 0.12
        private const val FAILURE_PENALTY = 0.25
        private const val DAY_MILLIS = 86_400_000.0
        private const val HALF_LIFE_MILLIS = 21.0 * DAY_MILLIS

        /**
         * Decode persisted lessons. Returns null when the payload is not a ledger this build
         * understands, so the caller can preserve the file instead of overwriting it.
         */
        fun decode(raw: String?, maxLessons: Int = MAX_LESSONS): LearningLedger? {
            if (raw.isNullOrBlank()) return null
            val root = JsonLite.parseObject(raw) ?: return null
            if (root.int("schema", -1) != SCHEMA_VERSION) return null
            val rawLessons = root.arr("lessons")
            if (rawLessons.size > maxLessons * 4) return null
            val lessons = rawLessons.mapNotNull { entry ->
                val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: return@mapNotNull null
                val key = map.strOrNull("k") ?: return@mapNotNull null
                val failureClass = runCatching { FailureClass.valueOf(map.str("c")) }.getOrNull() ?: return@mapNotNull null
                val kind = RecoveryKind.fromName(map.str("m")) ?: return@mapNotNull null
                val target = ResilienceTarget.fromName(map.str("t")) ?: ResilienceTarget.LOCAL
                Lesson(
                    signatureKey = key.take(64),
                    failureClass = failureClass,
                    kind = kind,
                    target = target,
                    tool = map.str("x").take(32),
                    succeeded = map.bool("s"),
                    note = map.str("n").take(MAX_NOTE_CHARS),
                    createdAt = map.long("at"),
                    episodeId = map.str("e").take(40)
                )
            }
            return LearningLedger(lessons.takeLast(maxLessons), maxLessons)
        }
    }
}
