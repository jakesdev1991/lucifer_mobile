package com.lucifer.mobile.data.llm

import android.content.Context
import com.lucifer.mobile.omni_bridge.llm.NemotronClient

/**
 * LuciferLLM — the app's model path, egress-gated.
 *
 * Prompts go to [NemotronClient], which is the only LLM route and opens its socket through
 * `privacy/EgressHttp` (recorded in the egress ledger as MODEL). The Kai sandbox is not used for
 * prompts.
 */
class LuciferLLM(context: Context) {

    private val client = NemotronClient(context.applicationContext)

    fun initialize() { /* NemotronClient is connection-per-request; nothing to warm */ }

    suspend fun generate(messages: List<LLMMessage>): LLMResponse {
        val prompt = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        return try {
            val text = client.chat(listOf("user" to prompt))
            LLMResponse(content = text, success = true, error = "")
        } catch (e: Exception) {
            LLMResponse(content = "", success = false, error = e.message ?: "model call failed")
        }
    }

    fun shutdown() { /* stateless client; nothing to release */ }
}

data class LLMMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String
)

data class LLMResponse(
    val success: Boolean,
    val content: String = "",
    val tokensUsed: Int = 0,
    val latencyMs: Int = 0,
    val error: String = ""
)
