package com.lucifer.mobile.surface

/**
 * Deterministic answers about Lucifer's own interfaces.
 *
 * A model asked "what can you do here?" will invent something plausible. This does not ask a model.
 * [SurfaceQuestions] decides whether a message is a question about the interface, and [SurfaceAnswers]
 * composes the reply from [SurfaceKnowledge] plus live [SurfaceFacts]. Running before the model means
 * the answer is free, instant, offline-capable, and cannot drift from the code that implements it.
 *
 * Deliberately conservative: a message that merely mentions "terminal" while asking about something
 * else is NOT captured, because a wrong interception is worse than a model answer with the surface
 * block attached. Anything not matched falls through to the normal path.
 */

enum class SurfaceQuestion {
    /** "Where am I talking to you from?" */
    WHERE,

    /** "What can you do here / in this overlay / from the launcher?" */
    CAPABILITIES,

    /** "What can't you do here?" */
    LIMITS,

    /** "What do you send / does this leak my data?" */
    DATA,

    /** "Will you ask before running something?" */
    CONFIRMATION,

    /** "Which interfaces can I reach you from?" */
    LIST,

    /** Not a question about the interface. */
    NONE;
}

object SurfaceQuestions {

    /** Longer messages are almost always about the user's task, not about the interface. */
    const val MAX_QUESTION_CHARS = 220

    private val WHERE = listOf(
        Regex("(?i)\\b(where (am i|are we)|which (interface|surface|channel|screen)|what (interface|surface|screen) (is this|am i))\\b"),
        Regex("(?i)\\bwhat am i (talking|speaking) (to|through|from|on)\\b"),
        Regex("(?i)\\bare you (in|on) the (launcher|overlay|terminal|core|app)\\b")
    )

    private val CAPABILITIES = listOf(
        // Tolerates the hedges people actually type ("actually", "really", "even").
        Regex("(?i)\\bwhat can (you|i) (actually |really |even )?do (here|from here|in this|on this|from this|in here)\\b"),
        Regex("(?i)\\bwhat are (your|his) (capabilities|abilities|powers) (here|on this)?\\b"),
        // Bare form only when nothing follows: "what can you do?" yes, "what can you do about X?" no.
        Regex("(?i)\\bwhat (can|could) you (actually |really )?do\\s*\\??\\s*$"),
        Regex("(?i)\\bcapabilities? (here|on this (surface|screen|interface))\\b"),
        Regex("(?i)\\bwhat (is|does) (this|the) (screen|panel|interface|surface|overlay) (do|let you)\\b"),
        Regex("(?i)\\bcan you (run|execute|open|control|see|read|type|launch) .*\\b(here|from here|in this|on this)\\b"),
        // Articles allowed: "run a shell", "send the command".
        Regex("(?i)\\bcan you (run|execute|type|launch|control) (a |an |the )?(commands?|shell|terminal|termux|vm)\\b")
    )

    private val LIMITS = listOf(
        Regex("(?i)\\bwhat can'?t (you|i) do (here|in this|on this|from here)\\b"),
        Regex("(?i)\\b(limits?|limitations?|restrictions?) (here|of this|on this (surface|screen|interface))\\b"),
        Regex("(?i)\\bwhat (are you|is he) (not )?(able|allowed) to do\\b"),
        Regex("(?i)\\bwhy can'?t you (run|see|control|open)\\b")
    )

    private val DATA = listOf(
        // Anchored: "what do you send *when/to the model/from here*" — not "what do you send in a header".
        Regex("(?i)\\bwhat (do you|does he) (send|upload|share|transmit) (when|to the model|to your model|from here|here|on this|to the assistant)\\b"),
        Regex("(?i)\\bwhat (leaves|goes out|is sent) (the device|this device|my phone)\\b"),
        Regex("(?i)\\bare you (spying|watching|recording|tracking)\\b"),
        Regex("(?i)\\b(privacy|data) (here|on this (surface|screen|interface))\\b"),
        Regex("(?i)\\bdoes (this|that) (leak|leave)\\b")
    )

    private val CONFIRMATION = listOf(
        Regex("(?i)\\b(will|do) you (ask|confirm|check) (me )?(before|first)\\b"),
        Regex("(?i)\\bdo i (need to|have to) (confirm|approve)\\b"),
        Regex("(?i)\\bis (it|this) (automatic|autonomous|unattended)\\b"),
        Regex("(?i)\\bcan you run (it|that|this) (without|with no) (asking|confirmation|approval)\\b")
    )

    private val LIST = listOf(
        Regex("(?i)\\b(what|which) (interfaces?|surfaces?|ways|places) (can i|do i|to)\\b"),
        Regex("(?i)\\bwhere (can|do) i (talk|reach|message) (to )?you\\b"),
        Regex("(?i)\\blist (your )?(interfaces|surfaces)\\b"),
        Regex("(?i)\\ball the ways (i can|to) (talk|reach) (to )?you\\b")
    )

    fun classify(raw: String): SurfaceQuestion {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_QUESTION_CHARS) return SurfaceQuestion.NONE
        // A question mark is a strong signal but not required: "tell me what you can do here".
        val looksLikeQuestion = text.contains('?') ||
            text.startsWith("what", true) || text.startsWith("which", true) ||
            text.startsWith("where", true) || text.startsWith("can", true) ||
            text.startsWith("do ", true) || text.startsWith("list", true) ||
            text.startsWith("tell me", true)
        if (!looksLikeQuestion) return SurfaceQuestion.NONE

        // Order matters: a question that names limits is a limits question even if it says "can you".
        if (LIMITS.any { it.containsMatchIn(text) }) return SurfaceQuestion.LIMITS
        if (DATA.any { it.containsMatchIn(text) }) return SurfaceQuestion.DATA
        if (CONFIRMATION.any { it.containsMatchIn(text) }) return SurfaceQuestion.CONFIRMATION
        if (WHERE.any { it.containsMatchIn(text) }) return SurfaceQuestion.WHERE
        if (CAPABILITIES.any { it.containsMatchIn(text) }) return SurfaceQuestion.CAPABILITIES
        if (LIST.any { it.containsMatchIn(text) }) return SurfaceQuestion.LIST
        return SurfaceQuestion.NONE
    }
}

object SurfaceAnswers {

    /**
     * A complete answer, or null when the message is not about the interface. The null case is the
     * important one: it lets ordinary conversation proceed with only the prompt block for grounding.
     */
    fun answer(raw: String, surface: Surface, facts: SurfaceFacts): String? {
        val kind = SurfaceQuestions.classify(raw)
        if (kind == SurfaceQuestion.NONE) return null
        val spec = SurfaceKnowledge.spec(surface)
        return when (kind) {
            SurfaceQuestion.WHERE -> buildString {
                appendLine("You are talking to me from ${surface.displayName} (${surface.id}).")
                appendLine(spec.whatItIs)
                appendLine()
                appendLine("How a message travels here: ${spec.transport}")
                appendLine()
                appendLine("Live right now: " + facts.snapshot().joinToString(" · "))
                append("I know this because the app tells me which surface I am on. I cannot see your screen.")
            }

            SurfaceQuestion.CAPABILITIES -> buildString {
                appendLine("Here is what I can actually do from ${surface.displayName}.")
                appendLine()
                appendLine(spec.whatItIs)
                appendLine()
                appendLine("What I can do here:")
                spec.canDo.forEach { appendLine("· $it") }
                appendLine()
                appendLine("What I cannot do here:")
                spec.cannotDo.forEach { appendLine("· $it") }
                appendLine()
                appendLine("Approval: ${spec.confirmation}")
                appendLine()
                appendLine("Live right now: " + facts.snapshot().joinToString(" · "))
                appendLine()
                append(
                    if (facts.networkOnline && facts.modelKeyConfigured) {
                        "These limits come from this app's code, not from the model's guess."
                    } else if (!facts.modelKeyConfigured) {
                        "No model key is configured, so answers here are local reasoning only — the " +
                            "capability list above is still exact, because it is compiled in."
                    } else {
                        "The network switch is OFF, so nothing is leaving the device; my answers are local " +
                            "reasoning and this list."
                    }
                )
            }

            SurfaceQuestion.LIMITS -> buildString {
                appendLine("Limits on ${surface.displayName}:")
                spec.cannotDo.forEach { appendLine("· $it") }
                appendLine()
                appendLine("Why: ${spec.limits}")
                appendLine()
                appendLine("Approval rule: ${spec.confirmation}")
                appendLine()
                appendLine("If you need something this surface cannot do, tell me the target — for example " +
                    "run it on TERMUX, on the VM, or locally — and I will say whether that one can.")
                append("Live right now: " + facts.snapshot().joinToString(" · "))
            }

            SurfaceQuestion.DATA -> buildString {
                appendLine("Where you are matters for data. On ${surface.displayName}:")
                appendLine(spec.leavesDevice)
                appendLine()
                appendLine("Rules that always hold:")
                SurfaceKnowledge.invariants.forEach { appendLine("· $it") }
                appendLine()
                appendLine("Right now the network switch is " + if (facts.networkOnline) "ON" else "OFF" +
                    ". " + when {
                        !facts.networkOnline -> "Nothing is leaving the device: requests are refused before a connection opens."
                        !facts.modelKeyConfigured -> "With no API key configured, model calls fail before sending anything."
                        else -> "Local shell, files and memory work with it off; only remote calls stop."
                    })
                append("Settings → Privacy shows the ledger of every outbound request, host and path only.")
            }

            SurfaceQuestion.CONFIRMATION -> buildString {
                appendLine("On ${surface.displayName}: ${spec.confirmation}")
                appendLine()
                appendLine("What that means in practice:")
                SurfaceKnowledge.invariants.take(2).forEach { appendLine("· $it") }
                appendLine()
                append(
                    if (facts.pendingConfirmation) {
                        "There is a command waiting for your confirmation right now."
                    } else {
                        "Nothing is waiting for confirmation at the moment. If I propose something that " +
                            "changes the device, you will see the exact command and target first."
                    }
                )
            }

            SurfaceQuestion.LIST -> SurfaceKnowledge.catalogue()

            SurfaceQuestion.NONE -> null
        }
    }
}
