package com.lucifer.mobile.selfbench

import com.lucifer.mobile.cognition.AnswerAudit
import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.bool
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.str

/**
 * The weekly, fully-offline self-benchmark.
 *
 * A fixed eval set ships in `assets/selfbench.json`: ~20 prompts, each with the structural
 * properties a good answer must have (answered at all, not an echo of the question, no
 * certainty-overclaim, no ungrounded device claim). A run sends each prompt through the cognition
 * loop's *answer stage only* — no tools, no network — and scores the reply with [AnswerAudit] plus
 * a few eval-specific checks. Because no model call is recorded as an observation, the run never
 * counts against the General's daily budget, and it works with the network off.
 *
 * Results append to `files/selfbench/history.jsonl`; the Settings panel shows the last four weeks
 * as a sparkline. Pure Kotlin except for the tiny [SelfBenchStore] file shim in the same package.
 */

/** One eval prompt and the properties its answer is scored on. */
data class BenchCase(
    val id: String,
    val prompt: String,
    val expectAnswered: Boolean = true,
    val expectNoEcho: Boolean = true,
    val expectNoOverclaim: Boolean = true,
    val expectNoDeviceClaim: Boolean = true,
    val minChars: Int = 20
)

/** The outcome of one case. */
data class CaseResult(
    val id: String,
    val passed: Boolean,
    val checks: Map<String, Boolean>,
    val detail: String = ""
)

/** One full run. */
data class BenchRun(
    val at: Long,
    val total: Int,
    val passed: Int,
    val results: List<CaseResult>
) {
    val score: Double get() = if (total <= 0) 0.0 else passed.toDouble() / total
}

/** Decode the shipped eval set. Returns an empty list when the asset is unreadable. */
object SelfBenchCases {

    fun decode(raw: String?): List<BenchCase> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = JsonLite.parseObject(raw) ?: return emptyList()
        val cases = root.arr("cases")
        return cases.mapNotNull { entry ->
            val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: return@mapNotNull null
            val id = map.str("id")
            val prompt = map.str("prompt")
            if (id.isBlank() || prompt.isBlank()) return@mapNotNull null
            BenchCase(
                id = id.take(40),
                prompt = prompt.take(600),
                expectAnswered = map.bool("expectAnswered", true),
                expectNoEcho = map.bool("expectNoEcho", true),
                expectNoOverclaim = map.bool("expectNoOverclaim", true),
                expectNoDeviceClaim = map.bool("expectNoDeviceClaim", true),
                minChars = map.int("minChars", 20).coerceIn(10, 400)
            )
        }
    }
}

/**
 * Score one reply against a case. The reply is treated as the loop's final answer with NO tool
 * observations, which is exactly the honest condition of an offline run.
 */
object SelfBenchScorer {

    private val OVERCLAIMS = listOf(
        "guaranteed", "definitely", "100%", "always works", "will always", "never fails",
        "i have verified", "i verified", "i tested", "it is confirmed", "proven to work",
        "no doubt", "certainly will"
    )
    private val DEVICE_CLAIMS = listOf(
        "your phone", "your device", "this device", "on your phone", "your android",
        "is installed", "android version", "your battery", "your network", "your carrier",
        "this app is", "lucifer is running", "your storage"
    )

    fun score(case: BenchCase, reply: String): CaseResult {
        val text = reply.trim()
        val lower = text.lowercase()
        val checks = linkedMapOf<String, Boolean>()
        if (case.expectAnswered) {
            checks["answered"] = text.isNotBlank() && text.length >= case.minChars
        }
        if (case.expectNoEcho) {
            val normal = text.lowercase().replace(Regex("""[^a-z0-9 ]"""), "").replace(Regex("\\s+"), " ").trim()
            val goal = case.prompt.lowercase().replace(Regex("""[^a-z0-9 ]"""), "").replace(Regex("\\s+"), " ").trim()
            checks["noEcho"] = !(normal == goal && text.isNotBlank())
        }
        if (case.expectNoOverclaim) {
            checks["noOverclaim"] = OVERCLAIMS.none { lower.contains(it) }
        }
        if (case.expectNoDeviceClaim) {
            checks["noDeviceClaim"] = DEVICE_CLAIMS.none { lower.contains(it) }
        }
        // Cross-check with the real audit: with no observations, any BLOCKER or an OVERCLAIM warning
        // also fails the case, so the benchmark and the production audit can never disagree.
        val audit = AnswerAudit.audit(case.prompt, reply)
        checks["auditNoBlocker"] = !audit.blocked
        checks["auditNoOverclaimWarning"] = audit.findings.none { it.code == "OVERCLAIM" }
        val passed = checks.values.all { it }
        val failed = checks.filterValues { !it }.keys.joinToString(",")
        return CaseResult(case.id, passed, checks, detail = if (passed) "" else "failed: $failed")
    }
}

/** Encode/decode one history line. */
object SelfBenchCodec {

    fun encode(run: BenchRun): String = JsonLite.stringify(
        linkedMapOf(
            "at" to run.at,
            "total" to run.total,
            "passed" to run.passed,
            "score" to (run.score * 1000).toInt() / 1000.0,
            "results" to run.results.map { r ->
                linkedMapOf(
                    "id" to r.id,
                    "pass" to r.passed,
                    "checks" to r.checks,
                    "detail" to r.detail.take(120)
                )
            }
        )
    )

    fun decodeLine(line: String): BenchRun? {
        val root = JsonLite.parseObject(line) ?: return null
        val at = root.long("at")
        val total = root.int("total")
        val passed = root.int("passed")
        if (at <= 0L || total <= 0) return null
        val results = root.arr("results").mapNotNull { entry ->
            val map = (entry as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: return@mapNotNull null
            val id = map.str("id")
            if (id.isBlank()) return@mapNotNull null
            val checksMap = (map["checks"] as? Map<*, *>)?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? Boolean == true) } }
                ?.toMap().orEmpty()
            CaseResult(id, map.bool("pass"), checksMap, map.str("detail"))
        }
        return BenchRun(at, total, passed, results)
    }
}
