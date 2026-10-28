package com.lucifer.mobile.tactician

import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.resilience.LearningLedger
import com.lucifer.mobile.resilience.ResilienceRedactor
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.str
import com.lucifer.mobile.resilience.stringList

/**
 * The General's senses: a bounded, local, redacted event ledger.
 *
 * Senses are limited to what Lucifer already does or can observe without extra permissions:
 * app install/uninstall events, terminal commands and their outcome, recovery episodes, workflow
 * progress, heartbeat sessions, camera *usage metadata* (never images), model calls, agent runs,
 * prompt edits, and coarse device state. No message content, no other apps' private data, no
 * keystrokes, no browsing history.
 */
enum class ObservationKind(val label: String) {
    APP_EVENT("app install/update"),
    TERMINAL("terminal"),
    RECOVERY("recovery"),
    WORKFLOW("workflow"),
    HEARTBEAT("heartbeat"),
    CAMERA("camera use"),
    MODEL("model call"),
    AGENT_RUN("agent run"),
    PROMPT_EDIT("prompt edit"),
    MCP("memory mcp"),
    DEVICE("device state"),
    USER("user request");

    companion object {
        fun from(raw: String?): ObservationKind? = entries.firstOrNull { it.name == raw }
    }
}

data class Observation(
    val kind: ObservationKind,
    val summary: String,
    val detail: String = "",
    val at: Long = System.currentTimeMillis(),
    val source: String = "",
    val severity: Int = 0,
    val tags: List<String> = emptyList(),
    /** Stable fingerprint for dedupe and evidence references. */
    val id: String = ""
) {
    val isFailure: Boolean get() = severity >= 2

    fun withId(): Observation {
        if (id.isNotBlank()) return this
        val key = com.lucifer.mobile.resilience.ResilienceText.stableKey(
            listOf(kind.name, summary.take(80), at.toString())
        )
        return copy(id = "o$key")
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "k" to kind.name,
        "s" to summary,
        "d" to detail,
        "at" to at,
        "src" to source,
        "sev" to severity,
        "t" to tags,
        "id" to id
    )

    companion object {
        const val MAX_SUMMARY = 180
        const val MAX_DETAIL = 600

        /** Build an observation with redaction and length limits already applied. */
        fun of(
            kind: ObservationKind,
            summary: String,
            detail: String = "",
            at: Long = System.currentTimeMillis(),
            source: String = "",
            severity: Int = 0,
            tags: List<String> = emptyList()
        ): Observation = Observation(
            kind = kind,
            summary = ResilienceRedactor.redact(summary.replace('\n', ' '), MAX_SUMMARY).take(MAX_SUMMARY),
            detail = ResilienceRedactor.redact(detail, MAX_DETAIL).take(MAX_DETAIL),
            at = at,
            source = source.take(40),
            severity = severity.coerceIn(0, 3),
            tags = tags.map { it.take(24) }.distinct().take(6)
        ).withId()

        fun fromJson(map: Map<String, Any?>): Observation? {
            val kind = ObservationKind.from(map.str("k")) ?: return null
            val summary = map.str("s").take(MAX_SUMMARY)
            if (summary.isBlank()) return null
            return Observation(
                kind = kind,
                summary = summary,
                detail = map.str("d").take(MAX_DETAIL),
                at = map.long("at"),
                source = map.str("src").take(40),
                severity = map.int("sev").coerceIn(0, 3),
                tags = map.stringList("t").map { it.take(24) }.take(6),
                id = map.str("id").take(40)
            ).withId()
        }
    }
}

/**
 * Ring buffer with per-kind caps so one noisy source cannot bury everything else.
 * Bounded, thread-safe, and serialisable for restart.
 */
class ObservationLedger(
    initial: List<Observation> = emptyList(),
    private val maxTotal: Int = MAX_TOTAL,
    private val maxPerKind: Int = MAX_PER_KIND
) {
    private val items = ArrayList<Observation>(initial.size.coerceAtMost(maxTotal))

    init {
        val sorted = initial.sortedBy { it.at }.takeLast(maxTotal)
        for (entry in sorted) items.add(entry)
        trim()
    }

    val size: Int get() = items.size

    @Synchronized
    fun add(observation: Observation): Observation {
        val entry = observation.withId()
        if (items.any { it.id == entry.id }) return entry
        items.add(entry)
        trim()
        return entry
    }

    @Synchronized
    fun addAll(observations: List<Observation>) {
        observations.forEach { add(it) }
    }

    @Synchronized
    fun all(): List<Observation> = items.toList()

    @Synchronized
    fun recent(withinMillis: Long, now: Long = System.currentTimeMillis()): List<Observation> =
        items.filter { now - it.at in 0..withinMillis }

    @Synchronized
    fun ofKind(kind: ObservationKind, limit: Int = 40): List<Observation> =
        items.filter { it.kind == kind }.takeLast(limit)

    @Synchronized
    fun counts(withinMillis: Long, now: Long = System.currentTimeMillis()): Map<ObservationKind, Int> =
        recent(withinMillis, now).groupingBy { it.kind }.eachCount()

    @Synchronized
    fun failures(withinMillis: Long, now: Long = System.currentTimeMillis()): List<Observation> =
        recent(withinMillis, now).filter { it.isFailure }

    @Synchronized
    fun clear() {
        items.clear()
    }

    /** Compact evidence block for the model. Oldest dropped first. */
    @Synchronized
    fun digest(windowMillis: Long, maxChars: Int = 6_000, now: Long = System.currentTimeMillis()): String {
        val window = recent(windowMillis, now)
        if (window.isEmpty()) return "No recorded observations in this window."
        val builder = StringBuilder()
        val counts = window.groupingBy { it.kind }.eachCount()
        builder.append("Observation counts: ")
            .append(counts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.label}=${it.value}" })
            .append('\n')
        val failures = window.filter { it.isFailure }
        if (failures.isNotEmpty()) {
            builder.append("Failures (${failures.size}):\n")
            failures.takeLast(12).forEach {
                builder.append("- [").append(it.id).append("] ").append(it.kind.label).append(": ")
                    .append(it.summary).append(" (").append(ageMinutes(it.at, now)).append(")\n")
            }
        }
        val others = window.filterNot { it.isFailure }.takeLast(30)
        if (others.isNotEmpty()) {
            builder.append("Recent activity:\n")
            for (entry in others) {
                builder.append("- ").append(entry.kind.label).append(": ").append(entry.summary.take(140)).append('\n')
                if (builder.length > maxChars) {
                    return builder.toString().take(maxChars) + "\n[truncated]"
                }
            }
        }
        return builder.toString().take(maxChars)
    }

    @Synchronized
    fun encode(): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "schema" to SCHEMA,
            "observations" to items.map { it.toJson() }
        )
    )

    private fun trim() {
        while (items.size > maxTotal) items.removeAt(0)
        val byKind = items.groupBy { it.kind }
        for ((_, list) in byKind) {
            if (list.size > maxPerKind) {
                val victims = list.take(list.size - maxPerKind).toSet()
                items.removeAll(victims)
            }
        }
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_TOTAL = 800
        const val MAX_PER_KIND = 200

        fun decode(raw: String?, maxTotal: Int = MAX_TOTAL): ObservationLedger? {
            if (raw.isNullOrBlank() || raw.length > 1_500_000) return null
            val root = JsonLite.parseObject(raw) ?: return null
            if (root.int("schema", -1) != SCHEMA) return null
            val list = root.arr("observations").mapNotNull { entry ->
                val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
                map?.let { Observation.fromJson(it) }
            }
            return ObservationLedger(list.takeLast(maxTotal), maxTotal)
        }

        fun ageMinutes(at: Long, now: Long = System.currentTimeMillis()): String {
            val minutes = ((now - at).coerceAtLeast(0L)) / 60_000
            return when {
                minutes < 60 -> "${minutes}m ago"
                minutes < 60 * 48 -> "${minutes / 60}h ago"
                else -> "${minutes / (60 * 24)}d ago"
            }
        }
    }
}

/** Recovery-specific evidence, decoded from the existing lesson store. */
data class RecoveryDigest(
    val signature: String,
    val failureClass: String,
    val attempts: Int,
    val successes: Int,
    val lastAt: Long,
    val note: String
)

object RecoveryHistory {
    /** Read the resilience lesson ledger without writing to it. */
    fun summarize(rawLessonsJson: String?, maxEntries: Int = 30): List<RecoveryDigest> {
        val ledger = LearningLedger.decode(rawLessonsJson) ?: return emptyList()
        return ledger.lessons()
            .groupBy { it.signatureKey }
            .map { (signature, lessons) ->
                RecoveryDigest(
                    signature = signature,
                    failureClass = lessons.last().failureClass.name,
                    attempts = lessons.size,
                    successes = lessons.count { it.succeeded },
                    lastAt = lessons.maxOf { it.createdAt },
                    note = lessons.last().note
                )
            }
            .sortedByDescending { it.lastAt }
            .take(maxEntries)
    }
}
