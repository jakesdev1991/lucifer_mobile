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

        private val SYSTEM_ACCESS_TERMS = Regex("(?i)\\b(shizuku|shizuku api|user.?service|binder|aidl|app_process|adb shell|appops|hidden[- ]api|vpnservice|phantom process|selinux|android internals|android system|android os|telemetry|packet capture|foreground service|doze|permission manager x)\\b")

        /** Ground Android privilege answers in Lucifer's actual implementation and upstream limits. */
        private val ANDROID_SYSTEMS_CONTEXT = """
            Android/Shizuku facts for this answer:
            - Shizuku brokers Binder requests through a server started by the owner as ADB shell (UID 2000) or root (UID 0). A live Binder or grant is not blanket authorization; Android API level, system-service checks, Linux identity, SELinux, and OEM policy still apply.
            - There are two distinct integration styles: ShizukuBinderWrapper forwards system-service Binder calls; a UserService runs the app's own code in a separate shell/root-identity process. Lucifer currently uses a custom AIDL UserService and ProcessBuilder("sh", "-c", command); it does NOT currently use ShizukuBinderWrapper for direct PackageManager/AppOps APIs.
            - Lucifer checks pingBinder, the Shizuku grant, server API version, and UID, then binds a UserService for a bounded command. It must not grant, revoke, or change Permission Manager X permissions; the owner manages those externally.
            - A UserService is not a normal app process; Context/ContentResolver/receiver assumptions may fail. Do not recommend generic hidden-API bypasses. Verify the exact operation on the actual Android build and report unsupported/denied results honestly.
            - Android 12+ phantom/cached-process policies, Doze, and OEM background limits can stop child processes. Do not promise process survival or recommend silently changing DeviceConfig/power settings; use supported, visible lifecycle APIs.
            - Shizuku does not itself expose network telemetry. Device-wide packet filtering is a separate Android VpnService with explicit VPN consent and privacy limits; a VPN alone does not decrypt HTTPS, and Shizuku does not change that.
            Treat these as implementation facts, not proof that any requested operation will succeed. Never infer root from a connected status.
        """.trimIndent()

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
            val persona = systemPromptFor(horseman, input)
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

        val conquest = try { llm.ask(systemPromptFor(Horseman.CONQUEST, input), input, 512) } catch (e: Exception) { "" }
        if (conquest.startsWith("[LLM]") || conquest.isBlank()) {
            return@withContext offlineDeliberation(input)
        }
        val war = try { llm.ask(systemPromptFor(Horseman.WAR, input), "Plan to attack:\n$conquest", 384) } catch (e: Exception) { "" }
        val verdict = try {
            llm.ask(
                systemPromptFor(Horseman.FAMINE, input),
                "Synthesize the final answer. Plan:\n$conquest\n\nAdversarial pass:\n$war\n\nUser request: $input",
                640
            )
        } catch (e: Exception) { "" }
        if (verdict.isBlank() || verdict.startsWith("[LLM]")) {
            conquest + if (war.isNotBlank() && !war.startsWith("[LLM]")) "\n\nAdversarial note: $war" else ""
        } else verdict
    }

    private fun systemPromptFor(horseman: Horseman, input: String): String {
        val persona = PERSONAS[horseman] ?: "You are a precise assistant."
        return if (SYSTEM_ACCESS_TERMS.containsMatchIn(input)) "$persona\n\n$ANDROID_SYSTEMS_CONTEXT" else persona
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
        return if (SYSTEM_ACCESS_TERMS.containsMatchIn(input)) {
            "$ANDROID_SYSTEMS_CONTEXT\n\nCouncil offline — this is the static implementation guide, not a live device capability check."
        } else {
            "Council offline — all NVIDIA routes dark. Corpus-grounded echo: $input"
        }
    }
}
