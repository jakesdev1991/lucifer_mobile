package com.lucifer.mobile.terminal

import android.content.Context
import kotlinx.coroutines.delay

/** Connects to the user's Debian guest through the configured strict SSH bridge,
 * then uses guest tmux so both Lucifer and the built-in Android Terminal app see
 * and control the same live PTY. Lucifer never accesses AVF internals directly. */
object LinuxVmInteractiveSession {
    private const val PANE = "lucifer:0.0"

    suspend fun hasSession(context: Context): Boolean =
        TerminalExecutor(context.applicationContext).executeVmDirect(
            "tmux has-session -t ${shellQuote("lucifer")} >/dev/null 2>&1"
        ).any { it.trim() == "[exit 0]" }

    suspend fun sendLine(context: Context, rawLine: String): List<String> {
        val line = rawLine.trim()
        if (line.isBlank() || line.length > 1_000 || line.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return listOf("[ERROR] Enter one terminal line (maximum 1,000 characters).")
        }
        if (!hasSession(context)) {
            return listOf("[SETUP] In the Debian guest, start `tmux new-session -A -s lucifer`; confirm SSH and Terminal port forwarding first.")
        }
        val target = shellQuote(PANE)
        val injection = "tmux send-keys -t $target -l ${shellQuote(line)} && tmux send-keys -t $target Enter"
        val sent = TerminalExecutor(context.applicationContext).executeVmDirect(injection)
        if (sent.any { it.startsWith("[ERROR]") }) return sent
        delay(900)
        return capturePane(context)
    }

    suspend fun capturePane(context: Context): List<String> {
        val target = shellQuote(PANE)
        return TerminalExecutor(context.applicationContext).executeVmDirect(
            "tmux capture-pane -p -t $target -S -50 || echo '[Lucifer] No active tmux session named lucifer.'"
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
