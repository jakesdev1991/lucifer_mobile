package com.lucifer.mobile.omni_bridge.kai_bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// LLM Bridge - simplified interface for LLM operations
class KaiLLMBridge(
    private val kaiBridge: KaiBridge
) {

    suspend fun initialize() {
        kaiBridge.connect()
    }

    suspend fun query(prompt: String, model: String = "nemotron-3-ultra", maxTokens: Int = 2048): LLMResponse {
        val result = kaiBridge.queryLlm(prompt, model, maxTokens)
        return result?.let {
            LLMResponse(
                content = it.text,
                success = true,
                tokensUsed = it.tokensUsed,
                latencyMs = it.latencyMs,
                error = ""
            )
        } ?: LLMResponse(content = "", success = false, error = "Not connected")
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