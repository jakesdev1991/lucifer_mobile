// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.terminal

class TerminalExecutor(private val context: android.content.Context) {
    suspend fun execute(command: String, target: Int): List<String> = emptyList()
    fun destinationDetails(target: Int): String = ""
}
