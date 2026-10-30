// Type-check-only stand-in with a fixture.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.memory

object MemoryFixture {
    @Volatile var found: String = ""
    @Volatile var failure: Exception? = null

    fun reset() {
        found = ""
        failure = null
    }
}

class LocalConversationMemory(private val context: android.content.Context) {
    fun search(query: String, limit: Int): String {
        MemoryFixture.failure?.let { throw it }
        return MemoryFixture.found
    }
}
