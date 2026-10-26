package com.lucifer.mobile.terminal

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.lucifer.mobile.security.SecretStore
import com.lucifer.mobile.shizuku.ShizukuAccess
import com.lucifer.mobile.shizuku.ShizukuTerminal
import com.lucifer.mobile.terminal.LinuxVmInteractiveSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
            val knownHosts = File(context.filesDir, "config/known_hosts")
            val pinStatus = if (knownHosts.isFile && knownHosts.length() > 0L) "known_hosts configured" else "NO HOST-KEY PIN"
            "SSH as ${vmUser()}@$host:${vmPort()} · $pinStatus · strict checking"
        }
        SHIZUKU -> "Shizuku · ${ShizukuAccess.status().message}"
        else -> "Unknown destination"
    }

    suspend fun execute(command: String, target: Int): List<String> = withContext(Dispatchers.IO) {
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

    private fun executeVm(command: String): List<String> {
        val output = mutableListOf<String>()
        val password = loadVmPassword()
        val host = vmHost()
        val port = vmPort()
        val knownHosts = File(context.filesDir, "config/known_hosts")

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
            val startWait = System.currentTimeMillis()
            var timedOut = false
            while (true) {
                while (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    if (stdout.length < 32_000) stdout.append(String(buffer, 0, bytesRead))
                }
                if (channel.isClosed && inputStream.available() == 0) break
                if (System.currentTimeMillis() - startWait >= VM_COMMAND_TIMEOUT_MS) {
                    timedOut = true
                    channel.disconnect()
                    break
                }
                Thread.sleep(40)
            }
            stdout.toString().lines().filter { it.isNotBlank() }.take(MAX_LOCAL_OUTPUT_LINES).forEach(output::add)
            if (stderr.size() > 0) {
                stderr.toString().take(8_000).lines().filter { it.isNotBlank() }.forEach { output.add("[stderr] $it") }
            }
            if (timedOut) output.add("[ERROR] VM command timed out after ${VM_COMMAND_TIMEOUT_MS / 1_000}s")
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
        SecretStore.read(context, "config/vm_ssh_password.txt")?.trim()?.takeIf { it.isNotEmpty() }

    /** VM SSH host — configurable (Settings → VM HOST); empty = not set. */
    private fun vmHost(): String {
        return try {
            val file = File(context.filesDir, "config/vm_host.txt")
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** SSH account for a configured VM (defaults to the legacy droid account). */
    private fun vmUser(): String {
        return try {
            val file = File(context.filesDir, "config/vm_user.txt")
            file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.matches(Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,31}$")) } ?: "droid"
        } catch (_: Exception) { "droid" }
    }

    /** VM SSH port — configurable; default 8022. */
    private fun vmPort(): Int {
        return try {
            val file = File(context.filesDir, "config/vm_port.txt")
            if (file.exists()) file.readText().trim().toIntOrNull() ?: 8022 else 8022
        } catch (e: Exception) {
            8022
        }
    }
}