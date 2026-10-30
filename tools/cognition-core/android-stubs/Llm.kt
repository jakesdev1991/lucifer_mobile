// Type-check-only stand-in with a fixture.
//
// The planner is real code: it builds the system prompt from the tool list, the surface and the learned
// advice, fences the last observation, and declines to return a transport error as a repair. The model
// itself is not what is under test, so the reply is programmable and every call is recorded.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.omni_bridge.llm

object LlmFixture {
    class Call(val system: String, val user: String, val maxTokens: Int)

    @Volatile var reply: String = "[LLM] no fixture reply set"
    val calls = mutableListOf<Call>()

    fun reset(reply: String = "[LLM] no fixture reply set") {
        this.reply = reply
        calls.clear()
    }
}

class NemotronClient(private val context: android.content.Context) {
    suspend fun ask(system: String, user: String, maxTokens: Int): String {
        LlmFixture.calls += LlmFixture.Call(system, user, maxTokens)
        return LlmFixture.reply
    }
}
