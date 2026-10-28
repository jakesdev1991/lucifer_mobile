// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.memory

class LocalConversationMemory(private val context: android.content.Context) {
    fun rememberFinding(title: String, details: String, importance: Int = 75) {}
}
