package com.lucifer.mobile.cognition

/**
 * The check that runs *before* an answer is shown.
 *
 * A model is fluent about everything, including things it has not checked. The council pipeline had no
 * stage between "the model wrote a paragraph" and "the user reads it", which is how a confident sentence
 * about a device becomes a wrong belief the user can only discover by testing. This is that stage, and
 * it is deliberately blunt — it cannot tell truth from falsehood, but it *can* tell whether the answer
 * was grounded in anything the loop actually observed, and it can refuse to attach confidence it has not
 * earned.
 *
 * Two outputs, both load-bearing:
 *
 *  - [Finding]s: what is wrong, in a form the runner can act on (re-ask once, or attach the caveat).
 *  - [Confidence]: a small, honest ladder. It is never HIGH without observations to point at.
 */
object AnswerAudit {

    const val MIN_ANSWER_CHARS = 12

    /** The tool whose results are allowed to ground a citation. Named once; the catalogue owns the id. */
    const val CORPUS_TOOL = "corpus_search"

    enum class Severity { BLOCKER, WARNING, NOTE }

    data class Finding(val code: String, val severity: Severity, val message: String)

    enum class Confidence(val label: String, val rank: Int) {
        NONE("none", 0),
        LOW("low", 1),
        MEDIUM("medium", 2),
        HIGH("high", 3)
    }

    data class Result(
        val findings: List<Finding>,
        val confidence: Confidence,
        val confidenceReason: String
    ) {
        val blocked: Boolean get() = findings.any { it.severity == Severity.BLOCKER }
        val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARNING }
    }

    /** Phrases that claim more certainty than a language model can have. Compared case-insensitively. */
    private val OVERCLAIMS = listOf(
        "guaranteed", "definitely", "100%", "always works", "will always", "never fails",
        "i have verified", "i verified", "i tested", "it is confirmed", "proven to work",
        "no doubt", "certainly will"
    )

    /** Statements about the user's own device — the class of claim that needs a device observation. */
    private val DEVICE_CLAIMS = listOf(
        "your phone", "your device", "this device", "on your phone", "your android",
        "is installed", "android version", "your battery", "your network", "your carrier",
        "this app is", "lucifer is running", "your storage"
    )

    /**
     * A line that looks like the loop's own control format. The parser already refuses to execute one of
     * these when it appears after `ANSWER:`, but a model that writes `ANSWER: TOOL: clock(now)` produces a
     * reply whose first line is a tool call nobody made. That reads as "he ran something", so it is
     * flagged: the text is prose, and nothing in it was executed.
     */
    private val CONTROL_LINE = Regex("""(?im)^\s*(TOOL|ACTION|CALL|THOUGHT|REASONING|THINKING)\s*:""")

    private val CITATION = Regex("""\[(Vol|Omega|ToE|corpus)[^\]]*]""", RegexOption.IGNORE_CASE)
    private val HEDGES = listOf("i think", "probably", "maybe", "possibly", "might be", "not sure")

    private val WORD = Regex("""[a-z0-9]{6,}""")

    /**
     * @param goal what was asked.
     * @param answer what the model produced (or the loop's fallback).
     * @param observations every tool result text, oldest first.
     * @param toolEffects the side effect of each tool that actually ran, so a device claim can be matched
     *        to a device read rather than to any observation at all.
     * @param toolIds the tools that actually ran. A citation of a corpus volume is only credible if the
     *        corpus was read this turn — a page read does not lend authority to "[Vol 12]".
     */
    fun audit(
        goal: String,
        answer: String,
        observations: List<String> = emptyList(),
        toolEffects: List<SideEffect> = emptyList(),
        toolIds: List<String> = emptyList()
    ): Result {
        val findings = mutableListOf<Finding>()
        val text = answer.trim()
        val lower = text.lowercase()

        if (text.isEmpty()) {
            findings += Finding("EMPTY", Severity.BLOCKER, "the model produced no answer at all")
            return Result(findings, Confidence.NONE, "nothing was produced")
        }
        if (text.length < MIN_ANSWER_CHARS && goal.trim().length > 40) {
            findings += Finding(
                "THIN",
                Severity.BLOCKER,
                "a ${text.length}-character reply to a ${goal.trim().length}-character question does not address it"
            )
        }

        val observationText = observations.joinToString("\n")
        val hasObservations = observations.any { it.isNotBlank() }
        val deviceObserved = toolEffects.any { it == SideEffect.DEVICE_READ }
        val outwardObserved = toolEffects.any { it == SideEffect.NETWORK_READ }

        if (hasObservations && !overlaps(text, observationText)) {
            findings += Finding(
                "UNGROUNDED",
                Severity.WARNING,
                "tools returned material, but the answer shares no wording with it — it may be reasoning " +
                    "around the evidence rather than from it"
            )
        }

        val overclaim = OVERCLAIMS.firstOrNull { lower.contains(it) }
        if (overclaim != null && !deviceObserved) {
            findings += Finding(
                "OVERCLAIM",
                Severity.WARNING,
                "\"$overclaim\" is a certainty this loop cannot have; nothing was verified against the device"
            )
        }

        val deviceClaim = DEVICE_CLAIMS.firstOrNull { lower.contains(it) }
        if (deviceClaim != null && !deviceObserved) {
            findings += Finding(
                "UNVERIFIED_DEVICE_CLAIM",
                Severity.WARNING,
                "the answer states something about this device (\"$deviceClaim\") but no device read was performed"
            )
        }

        val corpusRead = toolIds.any { it.equals(CORPUS_TOOL, ignoreCase = true) }
        if (CITATION.containsMatchIn(text) && !corpusRead) {
            findings += Finding(
                "CITES_WITHOUT_SOURCE",
                Severity.WARNING,
                if (hasObservations) "the answer cites a corpus volume, but the corpus was not read this turn"
                else "the answer cites a corpus volume, but no corpus read happened this turn"
            )
        }

        if (CONTROL_LINE.containsMatchIn(text)) {
            findings += Finding(
                "PROTOCOL_IN_ANSWER",
                Severity.WARNING,
                "the reply contains a line shaped like a tool call; nothing in it was executed, and it " +
                    "was not read as an instruction"
            )
        }

        if (normalise(text) == normalise(goal) && text.length > 0) {
            findings += Finding("ECHO", Severity.WARNING, "the answer restates the question")
        }

        val hedges = HEDGES.count { lower.contains(it) }
        if (hedges >= 3) {
            findings += Finding(
                "HEDGE_STACK",
                Severity.NOTE,
                "$hedges hedges — uncertainty stated once, plainly, reads as honesty; five times it reads as dodging"
            )
        }

        val confidence = when {
            findings.any { it.severity == Severity.BLOCKER } -> Confidence.NONE
            !hasObservations -> Confidence.LOW
            findings.any { it.severity == Severity.WARNING } -> Confidence.MEDIUM
            observations.size >= 2 -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }

        val reason = when (confidence) {
            Confidence.NONE -> "the reply was unusable, not merely uncertain"
            Confidence.LOW -> "no tool was consulted, so this is the model's own recollection"
            Confidence.MEDIUM -> "grounded in ${observations.count { it.isNotBlank() }} observation(s)" +
                if (findings.any { it.severity == Severity.WARNING }) ", with caveats listed" else ""
            Confidence.HIGH -> "two or more tool results, and every check below passed"
        }
        return Result(findings, confidence, reason)
    }

    /**
     * The single line appended to a deep answer. One line, no emoji, no apology — and it names what was
     * *not* checked, because that is the part a reader cannot infer.
     */
    fun footer(result: Result, observations: Int, deviceRead: Boolean): String {
        val checked = when {
            observations <= 0 -> "nothing was consulted"
            deviceRead -> "$observations tool result(s), including a device read"
            else -> "$observations tool result(s)"
        }
        val unchecked = if (deviceRead) "" else "; nothing was checked on the device"
        val caveat = if (result.warnings.isNotEmpty()) " · ${result.warnings.size} caveat(s) below" else ""
        return "Confidence: ${result.confidence.label.uppercase()} — $checked$unchecked$caveat."
    }

    /** Words of six characters or more, compared as sets. Cheap, and enough to spot a disconnected answer. */
    private fun overlaps(answer: String, observation: String): Boolean {
        if (observation.isBlank()) return true
        val answerWords = WORD.findAll(answer.lowercase()).map { it.value }.toSet()
        if (answerWords.isEmpty()) return false
        val observationWords = WORD.findAll(observation.lowercase()).map { it.value }.toSet()
        return answerWords.any { it in observationWords }
    }

    private fun normalise(text: String): String =
        text.lowercase().replace(Regex("""[^a-z0-9 ]"""), "").replace(Regex("""\s+"""), " ").trim()
}
