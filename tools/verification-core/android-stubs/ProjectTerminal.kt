// Compile-only stand-in for the real SSH bridge: the verifier only calls this one entry point, and the
// transport itself is not part of what this harness proves.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.terminal

import android.content.Context

class TerminalExecutor(private val context: Context) {
    internal suspend fun executeVmDirect(command: String): List<String> = emptyList()
    internal suspend fun executeVmDirect(
        command: String,
        timeoutMs: Long,
        maxLines: Int = 400
    ): List<String> = emptyList()
}
