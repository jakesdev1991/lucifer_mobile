package com.lucifer.mobile.terminal

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/** Opens one real Termux PTY (inside tmux) and provides a narrow bridge for Lucifer
 * to submit a line or snapshot the visible pane while that session remains open. */
object TermuxInteractiveSession {
    const val SESSION_NAME = "lucifer"

    private val toolSuite = listOf(
        "tmux", "bash", "coreutils", "findutils", "grep", "sed", "gawk", "curl", "wget",
        "git", "openssh", "python", "nodejs-lts", "clang", "make", "cmake", "pkg-config",
        "rust", "golang", "jq", "ripgrep", "fd", "fzf", "tree", "htop", "procps",
        "dnsutils", "iproute2", "nmap", "whois", "netcat-openbsd", "traceroute", "sqlite",
        "ffmpeg", "imagemagick", "pandoc", "zip", "unzip", "7zip", "zstd", "rsync",
        "vim", "nano", "less", "man", "shellcheck", "shfmt", "strace", "proot-distro",
        "termux-api", "ruby", "perl", "php"
    )

    /** Starts Termux's foreground RUN_COMMAND session; Termux owns the PTY and UI. */
    fun launch(context: Context, installToolSuite: Boolean = false): Boolean = try {
        val install = if (installToolSuite) {
            "pkg update -y || echo '[WARN] pkg update failed; continuing with cached indexes.'; " +
                "install_group(){ if ! pkg install -y \"${'$'}@\"; then for package in \"${'$'}@\"; do pkg install -y \"${'$'}package\" || echo \"[WARN] Could not install ${'$'}package\"; done; fi; }; " +
                "install_group ${toolSuite.joinToString(" ")}; "
        } else {
            ""
        }
        val script = buildString {
            append(install)
            append("if ! command -v tmux >/dev/null 2>&1; then ")
            append("echo 'tmux is missing; choose INSTALL TOOLKIT in Lucifer first.'; exec bash -l; ")
            append("fi; exec tmux new-session -A -s ")
            append(SESSION_NAME)
        }
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            // Termux value 0: switch to a new interactive session and open Termux.
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Lucifer interactive shell")
        }
        context.startService(intent) != null
    } catch (_: Exception) {
        false
    }

    suspend fun hasSession(context: Context): Boolean {
        val result = TerminalExecutor(context.applicationContext)
            .executeTermuxBackground("tmux has-session -t ${shellQuote(SESSION_NAME)} >/dev/null 2>&1", timeoutMillis = 15_000L)
        return result.any { it.trim() == "[exit 0]" }
    }

    suspend fun sendLine(context: Context, line: String): List<String> {
        val command = line.trim()
        if (command.isBlank() || command.length > 1_000 || command.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return listOf("[ERROR] Enter one terminal line (maximum 1,000 characters).")
        }
        if (!hasSession(context)) return listOf("[SETUP] Open the interactive Termux shell first so Lucifer can attach to its tmux PTY.")
        val inject = "tmux send-keys -t ${shellQuote("$SESSION_NAME:0.0")} -l ${shellQuote(command)} && tmux send-keys -t ${shellQuote("$SESSION_NAME:0.0")} Enter"
        val executor = TerminalExecutor(context.applicationContext)
        val sent = executor.executeTermuxBackground(inject, timeoutMillis = 15_000L)
        if (sent.any { it.startsWith("[ERROR]") || it.contains("not found", ignoreCase = true) }) return sent
        delay(900)
        return capturePane(context)
    }

    suspend fun capturePane(context: Context): List<String> {
        val target = shellQuote("$SESSION_NAME:0.0")
        return TerminalExecutor(context.applicationContext).executeTermuxBackground(
            "tmux capture-pane -p -t $target -S -50 || echo '[Lucifer] No active tmux session named $SESSION_NAME.'",
            timeoutMillis = 15_000L
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
