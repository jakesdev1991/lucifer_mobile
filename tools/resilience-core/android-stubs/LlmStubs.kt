// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.omni_bridge.llm

class NemotronClient(private val context: android.content.Context) {
    suspend fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        requestTimeoutMs: Int = 120_000,
        userInitiated: Boolean = true
    ): String = ""
}
