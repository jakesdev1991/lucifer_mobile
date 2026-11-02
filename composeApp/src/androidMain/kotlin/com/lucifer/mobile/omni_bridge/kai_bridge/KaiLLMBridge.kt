package com.lucifer.mobile.omni_bridge.kai_bridge

/**
 * LLM Bridge — routes through the app's real model client, not a Kai sandbox script.
 *
 * The old implementation shelled out to `/tmp/omni_bridge/llm/query.py` inside the Kai sandbox, which
 * nothing deploys. The honest path for an LLM query in this app is the egress-gated model client
 * (`privacy/EgressHttp` is the only socket owner; the call is recorded in the egress ledger as MODEL).
 * This bridge takes a fallback coroutine supplied by the caller and delegates to it; Kai is only used
 * as a read-only sandbox for non-network commands.
 */
class KaiLLMBridge(
    private val kaiBridge: KaiBridge,
    private val fallback: suspend (String, Int) -> String
) {
    suspend fun initialize() {
        kaiBridge.connect()
    }

    suspend fun query(prompt: String, model: String = "nemotron-3-ultra", maxTokens: Int = 2048): LLMResponse {
        // Delegate to the real, egress-gated model path. The Kai sandbox is not used for prompts.
        return try {
            val text = fallback(prompt, maxTokens.coerceIn(1, 8192))
            LLMResponse(content = text, success = true, error = "")
        } catch (e: Exception) {
            LLMResponse(content = "", success = false, error = e.message ?: "model call failed")
        }
    }

    fun shutdown() {
        kaiBridge.disconnect()
    }
}

data class LLMResponse(
    val content: String = "",
    val success: Boolean = false,
    val tokensUsed: Int = 0,
    val latencyMs: Int = 0,
    val error: String = ""
)
