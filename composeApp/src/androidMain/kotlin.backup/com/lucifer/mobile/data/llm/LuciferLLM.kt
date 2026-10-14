package com.lucifer.mobile.data.llm

import com.lucifer.mobile.omni_bridge.kai_bridge.KaiLLMBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuciferLLM {

    private val kaiBridge = KaiLLMBridge()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun initialize(context: android.content.Context) {
        scope.launch {
            kaiBridge.initialize()
        }
    }
    
    fun generate(messages: List<LLMMessage>): LLMResponse {
        return kaiBridge.generate(messages)
    }
    
    fun generateWithTools(
        messages: List<LLMMessage>,
        tools: List<Map<String, Any>>
    ): LLMResponse {
        return kaiBridge.generate_with_tools(messages, tools)
    }
    
    fun listModels(): List<String> {
        return kaiBridge.list_models()
    }
    
    fun checkHealth(): Boolean {
        return kaiBridge.check_health()
    }
    
    fun rotateKey() {
        kaiBridge.rotate_key()
    }
    
    fun getProviderStatus(): Map<String, Any> {
        return kaiBridge.get_provider_status()
    }
    
    fun setProviderConfig(config: Map<String, Any>) {
        kaiBridge.set_provider_config(config)
    }
    
    fun shutdown() {
        // Cleanup
    }
}

data class LLMMessage(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCalls: List<Map<String, Any>>? = null,
    val toolCallId: String? = null
)

data class LLMResponse(
    val content: String = "",
    val reasoningContent: String = "",
    val toolCalls: List<Map<String, Any>> = emptyList(),
    val model: String = "",
    val finishReason: String = "",
    val usage: Map<String, Any> = emptyMap(),
    val success: Boolean = false,
    val error: String = "",
    val statusCode: Int = 0
)