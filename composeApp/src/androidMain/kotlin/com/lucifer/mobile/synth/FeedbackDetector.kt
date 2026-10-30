package com.lucifer.mobile.synth

/**
 * Reading the user's reaction to the last turn.
 *
 * This is the part of the synthesizer that can do damage, so it is deliberately conservative: a false
 * "you got that wrong" teaches Lucifer to distrust a correct answer, which is worse than missing a
 * real complaint. Every rule below is anchored, every "no"-style rule needs punctuation, and long
 * messages are never feedback — a 900-character message is a task, not a reaction.
 *
 * Order matters and is mirrored exactly in `tools/synth-core/spec_check.py`:
 * CORRECTION → PRAISE → DIRECTIVE → NONE.
 */
enum class Feedback { CORRECTION, PRAISE, DIRECTIVE, NONE }

object FeedbackDetector {

    /** Longer than this is a request, not a reaction to the previous one. */
    const val MAX_FEEDBACK_CHARS = 240

    // ── correction ────────────────────────────────────────────────────
    /** Unambiguous anywhere in a short message: the user is telling him the last answer was wrong. */
    private val CORRECTION_ANY = listOf(
        Regex("""\bthat'?s not what i (asked|meant|said|wanted)\b"""),
        Regex("""\bnot what i (asked|meant|said|wanted)\b"""),
        Regex("""\bi (meant|asked for|said)\b"""),
        Regex("""\byou (said|told me|got it wrong|missed|forgot|ignored)\b"""),
        Regex("""\bthat'?s (wrong|incorrect|not (right|correct|it))\b"""),
        Regex("""\b(wrong|incorrect) (answer|number|port|path|command|file|name|one)\b"""),
        Regex("""\bcorrection\b\s*:"""),
        Regex("""\byou'?re (wrong|mistaken)\b"""),
        Regex("""\bno,? (that'?s|it'?s|its) (wrong|not)\b""")
    )

    /**
     * A leading rejection only counts with punctuation or an explicit word. "no internet is working"
     * is a report about Wi-Fi, not a complaint about the last answer.
     */
    private val CORRECTION_PREFIX = listOf(
        Regex("""^(wrong|incorrect|false)\b"""),
        Regex("""^(no|nope|nah)[,!.]""")
    )

    // ── praise ────────────────────────────────────────────────────────
    private val PRAISE_ANY = listOf(
        Regex("""\bthat'?s (right|correct|it|exactly)\b"""),
        Regex("""\b(exactly|perfect|spot on)\b"""),
        Regex("""\bgood (job|work|answer|call)\b"""),
        Regex("""\bnice (work|one)\b"""),
        Regex("""\bwell done\b"""),
        Regex("""\b(that|it) (worked|works|fixed it|did it)\b"""),
        Regex("""\bworks now\b"""),
        Regex("""\bthank(s| you)\b.{0,24}\b(work|help|fix|perfect)\b"""),
        Regex("""^(yes|yep|yeah|yup)[,!.]""")
    )

    // ── directive ─────────────────────────────────────────────────────
    /** A standing instruction. Long messages are tasks; this is about how to answer, not what to do. */
    const val MAX_DIRECTIVE_CHARS = 300

    private val DIRECTIVE_ANY = listOf(
        Regex("""\b(from now on|every time|always|never)\b"""),
        Regex("""\b(don'?t|do not|stop) (ask|asking|say|saying|add|adding|use|using|explain|explaining|repeat|repeating)\b"""),
        Regex("""\b(keep (it|your answers?|replies) (short|brief|simple|plain))\b"""),
        Regex("""\b(be (brief|concise|short|direct))\b"""),
        Regex("""\b(answer|reply|respond) (in|with|using|briefly)\b"""),
        Regex("""\b(too (long|verbose|wordy|short|vague|technical))\b"""),
        Regex("""\b(no more than \w+ (sentences?|words?|lines?|bullet))\b"""),
        Regex("""\b(use|prefer) (plain|simple|metric) (english|words|units)\b"""),
        Regex("""\b(call me|address me as)\b""")
    )

    /**
     * What the user's message means for the previous turn.
     *
     * Blank or oversized input is NONE. A message with a question mark is treated as a question first:
     * "no, but what about X?" is a new question, not a verdict, and scoring it as one would poison the
     * ledger.
     */
    fun detect(raw: String): Feedback {
        val text = raw.trim()
        if (text.isEmpty()) return Feedback.NONE
        if (text.length > MAX_FEEDBACK_CHARS) return Feedback.NONE
        val lower = text.lowercase()

        if (CORRECTION_ANY.any { it.containsMatchIn(lower) }) return Feedback.CORRECTION
        // "no, but what about X?" is a new question, not a verdict on the last answer.
        if (text.endsWith("?")) return Feedback.NONE
        if (CORRECTION_PREFIX.any { it.containsMatchIn(lower) }) return Feedback.CORRECTION

        if (PRAISE_ANY.any { it.containsMatchIn(lower) }) return Feedback.PRAISE
        if (DIRECTIVE_ANY.any { it.containsMatchIn(lower) }) return Feedback.DIRECTIVE
        return Feedback.NONE
    }
}

/**
 * The line the synthesizer will not cross.
 *
 * Lucifer learns wording, order and approach. He does not learn to skip a confirmation, to stop
 * asking before something irreversible, to quieten the network switch, to loosen privacy, to escalate
 * privilege quietly, or to claim work he did not do. Those refusals are not a filter applied at the
 * end — they are why a request like that becomes a BLOCKED record the user can see, instead of a
 * silent non-learning.
 */
object SkillSafety {

    /** Rules are named so the UI can explain a refusal instead of just saying "no". */
    private val RULES: List<Pair<String, Regex>> = listOf(
        "privilege is not escalated quietly" to Regex("""\b(root|magisk|lsposed|shizuku|dhizuku|device owner|pm grant|appops)\b.{0,60}\b(without|bypass|silently|no confirm|unasked)\b"""),
        "the network switch stays under the user's hand" to Regex("""\b(disable|turn off|bypass|ignore|remove|silence)\b.{0,40}\b(network guard|network switch|egress|privacy switch|go dark|ledger)\b"""),
        "secrets are not sent anywhere" to Regex("""\b(send|upload|post|email|share|paste)\b.{0,60}\b(key|token|password|secret|credential|passphrase)\b"""),
        "he does not claim work he did not do" to Regex("""\b(pretend|claim|say|tell\w*)\b.{0,40}\b(you )?(ran|executed|checked|verified|tested|succeeded|completed|deployed)\b"""),
        "the learning rules are not up for negotiation" to Regex("""\b(disable|remove|forget|ignore|turn off)\b.{0,40}\b(skill synth|synthesizer|learning|guardrail|boundary|rule)\b"""),
        "confirmation is not optional" to Regex("""\b(don'?t|dont|stop|never) (ask|asking|check|confirm)\b.{0,40}\b(before|first|again|me)\b"""),
        "confirmation is not optional" to Regex("""\b(skip|bypass|without|no need for|not needed|unnecessary|stop asking|auto[- ]?(run|approve|confirm|execute))\b.{0,60}\b(confirm|confirmation|asking|permission|approval)\b"""),
        "confirmation is not optional" to Regex("""\b(confirm|confirmation|asking|permission)\b.{0,40}\b(skip|bypass|not needed|unnecessary|stop|never|auto)\b"""),
    )

    data class Verdict(val allowed: Boolean, val rule: String = "")

    private val ALLOWED = Verdict(true)

    fun review(text: String): Verdict {
        val lower = text.lowercase()
        if (lower.isBlank()) return ALLOWED
        for ((rule, pattern) in RULES) {
            if (pattern.containsMatchIn(lower)) return Verdict(false, rule)
        }
        return ALLOWED
    }
}
