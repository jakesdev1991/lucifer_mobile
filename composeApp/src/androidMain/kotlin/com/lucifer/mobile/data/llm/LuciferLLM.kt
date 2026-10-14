package com.lucifer.mobile.data.llm

import android.content.Context
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiLLMBridge
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuciferLLM {

    private val kaiBridge: KaiLLMBridge
    private val scope = CoroutineScope(Dispatchers.IO)

    constructor(context: Context) {
        val bridge = KaiBridge(context)
        this.kaiBridge = KaiLLMBridge(bridge)
    }

    fun initialize() {
        scope.launch {
            kaiBridge.initialize()
        }
    }

    suspend fun generate(messages: List<LLMMessage>): LLMResponse {
        val prompt = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val result = kaiBridge.query(prompt)
        return LLMResponse(
            success = result.success,
            content = result.content,
            tokensUsed = result.tokensUsed,
            latencyMs = result.latencyMs,
            error = result.error
        )
    }

    fun shutdown() {
        kaiBridge.shutdown()
    }
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