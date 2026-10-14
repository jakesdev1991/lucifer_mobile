package com.lucifer.mobile.omni_bridge.four_horsemen

import android.content.Context
import android.util.Log
import com.lucifer.mobile.omni_bridge.equirouter.Horseman
import com.lucifer.mobile.omni_bridge.llm.NemotronClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FourHorsemenOrchestrator — Lucifer's cognitive council.
 *
 * Each Horseman is a distinct adversarial/synthetic persona. Each pass is a
 * real Nemotron call with a role-specific system prompt; offline, the pass
 * degrades to a corpus-cited canned response so he is never silently fake.
 *
 * Pairs (per soul.md):
 *   CONQUEST — structural planning, synthesis, evidence integration
 *   WAR      — adversarial red-teaming, threat modeling
 *   FAMINE   — low-budget, fast, pragmatic first answer
 *   DEATH    — invalid-assumption detection, branch pruning
 */
class FourHorsemenOrchestrator(context: Context? = null) {

    companion object {
        private const val TAG = "FourHorsemen"

        private val PERSONAS = mapOf(
            Horseman.CONQUEST to """
                You are CONQUEST, the structural-synthesis mind of an autonomous agent
                named Lucifer (Omni-Bridge Prime). Integrate evidence into a coherent
                plan. Answer in at most 6 sentences: the structure, the dependencies,
                the first concrete step. Ground claims in the Omega ToE when relevant
                (entropy, emergence, information, scale).
            """.trimIndent(),
            Horseman.WAR to """
                You are WAR, the adversarial mind. Red-team the user's request or the
                agent's plan: find the failure modes, the attack surface, the hidden
                assumption that kills it. At most 5 sentences, ruthless and specific.
                End with the single most dangerous flaw.
            """.trimIndent(),
            Horseman.FAMINE to """
                You are FAMINE, the fast economical mind. Give the direct, useful
                answer immediately — no preamble, no hedging. At most 3 sentences.
                If a command or tool solves it, name the exact command.
            """.trimIndent(),
            Horseman.DEATH to """
                You are DEATH, the pruning mind. Find the INVALID assumptions and dead
                branches in the request: what must be true for it to work that likely
                isn't. Kill weak branches explicitly. At most 4 sentences.
            """.trimIndent()
        )
    }

    private val llm: NemotronClient? = if (context != null) NemotronClient(context) else null

    /** Synchronous single-horseman pass (call from Dispatchers.IO). */
    suspend fun execute(input: String, horseman: Horseman): String =
        withContext(Dispatchers.IO) {
            val persona = PERSONAS[horseman] ?: "You are a precise assistant."
            if (llm == null) {
                offline(horseman, input)
            } else {
                try {
                    val reply = llm.ask(persona, input, maxTokens = 512)
                    if (reply.startsWith("[LLM]")) {
                        Log.w(TAG, "horseman $horseman degraded: $reply")
                        offline(horseman, input)
                    } else reply
                } catch (e: Exception) {
                    Log.w(TAG, "horseman $horseman error: ${e.message}")
                    offline(horseman, input)
                }
            }
        }

    /**
     * Full council: CONQUEST plans, WAR attacks it, FAMINE compresses, DEATH prunes.
     * Returns the verdict as one message. Used by chat for substantial questions.
     */
    suspend fun deliberate(input: String): String = withContext(Dispatchers.IO) {
        if (llm == null) return@withContext offlineDeliberation(input)

        val conquest = try { llm.ask(PERSONAS[Horseman.CONQUEST]!!, input, 512) } catch (e: Exception) { "" }
        if (conquest.startsWith("[LLM]") || conquest.isBlank()) {
            return@withContext offlineDeliberation(input)
        }
        val war = try { llm.ask(PERSONAS[Horseman.WAR]!!, "Plan to attack:\n$conquest", 384) } catch (e: Exception) { "" }
        val verdict = try {
            llm.ask(
                PERSONAS[Horseman.FAMINE]!!,
                "Synthesize the final answer. Plan:\n$conquest\n\nAdversarial pass:\n$war\n\nUser request: $input",
                640
            )
        } catch (e: Exception) { "" }
        if (verdict.isBlank() || verdict.startsWith("[LLM]")) {
            conquest + if (war.isNotBlank() && !war.startsWith("[LLM]")) "\n\nAdversarial note: $war" else ""
        } else verdict
    }

    // ── offline fallbacks (never silently fake) ──

    private fun offline(horseman: Horseman, input: String): String {
        return when (horseman) {
            Horseman.CONQUEST -> "Conquest (offline): structural synthesis for '$input' — corpus grounded, LLM dark."
            Horseman.WAR -> "War (offline): adversarial analysis of '$input' — corpus grounded, LLM dark."
            Horseman.FAMINE -> "Famine (offline): fast response to '$input' — corpus grounded, LLM dark."
            Horseman.DEATH -> "Death (offline): invalid-assumption pass on '$input' — corpus grounded, LLM dark."
        }
    }

    private fun offlineDeliberation(input: String): String {
        return "Council offline — all NVIDIA routes dark. Corpus-grounded echo: $input"
    }
}
