@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.omni_bridge.llm

class NemotronClient(private val context: android.content.Context) {
    suspend fun ask(system: String, user: String, maxTokens: Int): String = "[LLM] stub"
}
