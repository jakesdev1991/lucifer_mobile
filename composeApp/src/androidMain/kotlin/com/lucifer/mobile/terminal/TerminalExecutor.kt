package com.lucifer.mobile.terminal

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.security.SecretStore
import com.lucifer.mobile.shizuku.ShizukuAccess
import com.lucifer.mobile.shizuku.ShizukuTerminal
import com.lucifer.mobile.terminal.LinuxVmInteractiveSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** POSIX-ish account name, matching the validation on the settings screen that writes it. */
private val VM_USER_RE = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,31}$")

class TerminalExecutor(private val context: Context) {

    companion object {
        private const val LOCAL = 0
        private const val TERMUX = 1
        private const val VM = 2
        private const val SHIZUKU = 3
        private const val LOCAL_TIMEOUT_SECONDS = 10L
        private const val MAX_LOCAL_OUTPUT_LINES = 400
        private const val VM_COMMAND_TIMEOUT_MS = 30_000L
    }

    fun destinationDetails(target: Int): String = when (target) {
        LOCAL -> "Android app sandbox"
        TERMUX -> "Termux environment"
        VM -> {
            val host = vmHost().ifBlank { "host not configured" }
            val pinStatus = if (AppConfig.isSet(context, AppConfig.KNOWN_HOSTS)) "known_hosts configured" else "NO HOST-KEY PIN"
            "SSH as ${vmUser()}@$host:${vmPort()} · $pinStatus · strict checking"
        }
        SHIZUKU -> "Shizuku · ${ShizukuAccess.status().message}"
        else -> "Unknown destination"
    }

    suspend fun execute(command: String, target: Int): List<String> = withContext(Dispatchers.IO) {
        TerminalCommandPolicy.refusalReason(command)?.let { reason ->
            return@withContext listOf("[BLOCKED] $reason")
        }
        return@withContext when (target) {
            LOCAL -> executeLocal(command)
            TERMUX -> if (TermuxInteractiveSession.hasSession(context)) {
                TermuxInteractiveSession.sendLine(context, command)
            } else executeTermux(command)
            VM -> if (LinuxVmInteractiveSession.hasSession(context)) {
                LinuxVmInteractiveSession.sendLine(context, command)
            } else executeVm(command)
            SHIZUKU -> ShizukuTerminal.execute(context, command)
            else -> listOf("[ERROR] Unknown target: $target")
        }
    }

    private fun executeLocal(command: String): List<String> {
        val output = mutableListOf<String>()
        var process: Process? = null
        try {
            val started = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started

            // Drain output concurrently so a full pipe cannot prevent the timeout from firing.
            val running = started
            val reader = thread(name = "lucifer-terminal-output", isDaemon = true) {
                running.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.size < MAX_LOCAL_OUTPUT_LINES) output.add(line)
                        }
                    }
                }
            }

            val completed = started.waitFor(LOCAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                started.destroyForcibly()
                started.waitFor()
                synchronized(output) { output.add("[ERROR] Command timed out after ${LOCAL_TIMEOUT_SECONDS}s") }
            }
            reader.join(1_000)
            synchronized(output) {
                if (output.size >= MAX_LOCAL_OUTPUT_LINES) output.add("[INFO] Output truncated")
                output.add("[exit ${started.exitValue()}]")
            }
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            output.add("[ERROR] Command interrupted")
        } catch (e: Exception) {
            process?.destroyForcibly()
            output.add("[ERROR] ${e.message}")
        }
        synchronized(output) {
            if (output.size == 1) output.add(0, "[INFO] app sandbox — no root")
        }
        return output
    }

    internal suspend fun executeTermuxBackground(command: String, timeoutMillis: Long = 90_000L): List<String> =
        withContext(Dispatchers.IO) { executeTermux(command, timeoutMillis) }

    private suspend fun executeTermux(command: String, timeoutMillis: Long = 90_000L): List<String> {
        return try {
            val result = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val executionId = TermuxResultRegistry.nextId()
                    TermuxResultRegistry.register(executionId, continuation)
                    continuation.invokeOnCancellation { TermuxResultRegistry.remove(executionId) }

                    val resultIntent = Intent(context, TermuxResultService::class.java)
                        .putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)
                    val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pendingIntent = PendingIntent.getService(
                        context,
                        executionId,
                        resultIntent,
                        PendingIntent.FLAG_ONE_SHOT or mutableFlag
                    )
                    val request = Intent("com.termux.RUN_COMMAND").apply {
                        setClassName("com.termux", "com.termux.app.RunCommandService")
                        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                        putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
                    }
                    val started = context.startService(request)
                    if (started == null) {
                        TermuxResultRegistry.complete(
                            executionId,
                            listOf("[ERROR] Termux was not found or its RUN_COMMAND service is unavailable.")
                        )
                    }
                }
            }
            result ?: listOf("[ERROR] Termux command timed out after ${timeoutMillis / 1_000} seconds")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(
                "[ERROR] Could not run a command in Termux.",
                "[SETUP] Install Termux, enable allow-external-apps=true, and grant Lucifer the ‘Run commands in Termux environment’ permission.",
                "[DETAIL] ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    internal suspend fun executeVmDirect(command: String): List<String> =
        withContext(Dispatchers.IO) { executeVm(command) }

    /**
     * The same strict SSH path, with the caller's own wall clock. Lean verification jobs legitimately
     * run for minutes where an interactive command should not, so the timeout is a parameter here and
     * clamped — never unbounded, and never shorter than the interactive default.
     */
    internal suspend fun executeVmDirect(
        command: String,
        timeoutMs: Long,
        maxLines: Int = MAX_LOCAL_OUTPUT_LINES
    ): List<String> = withContext(Dispatchers.IO) {
        executeVm(command, timeoutMs.coerceIn(10_000L, 1_900_000L), maxLines.coerceIn(50, 4_000))
    }

    private fun executeVm(
        command: String,
        timeoutMs: Long = VM_COMMAND_TIMEOUT_MS,
        maxLines: Int = MAX_LOCAL_OUTPUT_LINES
    ): List<String> {
        val output = mutableListOf<String>()
        val password = loadVmPassword()
        val host = vmHost()
        val port = vmPort()
        val knownHosts = AppConfig.file(context, AppConfig.KNOWN_HOSTS)

        if (host.isBlank() || !host.matches(Regex("^[A-Za-z0-9._:%-]+$")) || port !in 1..65535) {
            output.add("[SETUP] Enter a valid VM hostname/IP and port in Settings.")
            return output
        }
        if (!knownHosts.isFile || knownHosts.length() == 0L) {
            output.add("[SECURITY] VM execution is disabled until a verified SSH known_hosts entry is saved in Settings.")
            output.add("Obtain the host key over a trusted channel and verify its fingerprint before saving it.")
            return output
        }

        if (password.isNullOrBlank()) {
            output.add("[ERROR] Set VM SSH password in Settings first")
            return output
        }

        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val jsch = JSch()
            jsch.setKnownHosts(knownHosts.absolutePath)
            // Refuse unknown or changed SSH host keys; only a verified known_hosts
            // entry can authorize this connection.
            session = jsch.getSession(vmUser(), host, port)
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password")
            session.connect(5000)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stderr = java.io.ByteArrayOutputStream()
            channel.setErrStream(stderr)
            channel.connect(5000)

            val inputStream = channel.inputStream
            val buffer = ByteArray(2048)
            val stdout = StringBuilder()
            // Monotonic clock: a wall-clock read is what a timeout must not depend on, because NTP or a
            // manual time change mid-command would otherwise cut the wait short or extend it forever.
            val startWait = SystemClock.elapsedRealtime()
            var timedOut = false
            while (true) {
                while (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    if (stdout.length < 32_000) stdout.append(String(buffer, 0, bytesRead))
                }
                if (channel.isClosed && inputStream.available() == 0) break
                if (SystemClock.elapsedRealtime() - startWait >= timeoutMs) {
                    timedOut = true
                    channel.disconnect()
                    break
                }
                Thread.sleep(40)
            }
            stdout.toString().lines().filter { it.isNotBlank() }.take(maxLines).forEach(output::add)
            if (stderr.size() > 0) {
                stderr.toString().take(8_000).lines().filter { it.isNotBlank() }.forEach { output.add("[stderr] $it") }
            }
            if (timedOut) output.add("[ERROR] VM command timed out after ${timeoutMs / 1_000}s")
            output.add("[exit ${channel.exitStatus}]")
        } catch (e: Exception) {
            output.add("[ERROR] Debian VM SSH failed at $host:$port. Verify host/port, saved password, and the matching verified known_hosts entry.")
            output.add("[ERROR] ${e.message}")
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
        return output
    }

    private fun loadVmPassword(): String? =
        SecretStore.read(context, AppConfig.VM_SSH_PASSWORD)?.trim()?.takeIf { it.isNotEmpty() }

    /** VM SSH host — configurable (Settings → VM HOST); empty = not set. */
    private fun vmHost(): String = AppConfig.readText(context, AppConfig.VM_HOST).orEmpty()

    /** SSH account for a configured VM (defaults to the legacy droid account). */
    private fun vmUser(): String =
        AppConfig.readText(context, AppConfig.VM_USER)
            ?.takeIf { it.matches(VM_USER_RE) } ?: "droid"

    /** VM SSH port — configurable; default 8022. */
    // Clamped, like the screen that writes it: a hand-edited 99999 must not reach ssh.
    private fun vmPort(): Int = AppConfig.readInt(context, AppConfig.VM_PORT, 8022, 1..65535)
}