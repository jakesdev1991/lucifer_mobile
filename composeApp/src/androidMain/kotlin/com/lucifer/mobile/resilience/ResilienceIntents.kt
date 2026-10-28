package com.lucifer.mobile.resilience

/**
 * Offline detection of "I'm stuck / look it up / what did you learn" requests.
 *
 * Kept pure and separate from the router so the phrases that must never become a shell command
 * are covered by the host test harness. A model route can still classify this intent online;
 * this is the guaranteed floor when the model is unavailable.
 */
enum class ResilienceIntentKind { INVESTIGATE, RESEARCH, LESSONS, PLAN }

data class ResilienceIntent(val kind: ResilienceIntentKind, val query: String = "")

object ResilienceIntents {

    private val lessons = Regex(
        "\\b(what (?:have|did) you learn(?:ed)?|lessons? (?:learned|so far)|show (?:me )?(?:your )?lessons|" +
            "what do you know about (?:the )?(?:last )?failures?|learning progress|what have you figured out)\\b"
    )

    private val investigate = Regex(
        "\\b(figure out (?:what|why)|why did (?:that|it|this) fail|why (?:does|did) (?:it|this|that) keep failing|" +
            "get unstuck|i(?:'| a)?m stuck|(?:we(?:'| a)?re) stuck|investigate|debug (?:this|it|that)|" +
            "run the (?:vm|virtual machine|machine)|check the (?:vm|machine)|diagnose (?:this|it|that)|" +
            "what(?:'| i)?s going on|find out what happened|dig into (?:this|it))\\b"
    )

    private val plan = Regex(
        "\\b(what (?:should|do) (?:we|i|you) (?:try|do) next|what next|next move|make a plan|recovery plan|" +
            "how do (?:we|i) (?:fix|get past|work around) (?:this|it)|give me options)\\b"
    )

    private val research = Regex(
        "\\b(research|look (?:it|this|that|something) up|search the (?:web|internet)|web search|google it|" +
            "scrape|find (?:out )?(?:online|on the web)|browse the web for|check the docs)\\b"
    )

    private val researchPrefix = Regex(
        "^(?:please\\s+)?(?:can you\\s+)?(?:(?:research|look (?:it|this|that|something) up|look up)|" +
            "search the (?:web|internet)(?: for)?|search for|web search(?: for)?|google|scrape|" +
            "browse the web for|check the docs(?: for)?)\\s*(?:for me)?\\s*",
        RegexOption.IGNORE_CASE
    )

    /** Order matters: learning/understanding beats "plan", which beats a bare "research". */
    fun detect(raw: String): ResilienceIntent? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase()
        if (lessons.containsMatchIn(lower)) return ResilienceIntent(ResilienceIntentKind.LESSONS)
        if (investigate.containsMatchIn(lower)) return ResilienceIntent(ResilienceIntentKind.INVESTIGATE)
        if (plan.containsMatchIn(lower)) return ResilienceIntent(ResilienceIntentKind.PLAN)
        if (research.containsMatchIn(lower)) {
            val query = researchPrefix.replace(text, "").trim().trim('.', '!', '?', ',', ';', ':')
            return ResilienceIntent(ResilienceIntentKind.RESEARCH, query.take(240))
        }
        return null
    }
}
