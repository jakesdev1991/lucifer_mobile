package com.lucifer.mobile.shizuku

import androidx.annotation.Keep
import com.lucifer.mobile.terminal.TerminalCommandPolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs inside Shizuku's UserService process as shell (ADB) or root, depending on
 * the provider the phone owner configured. All calls originate from Lucifer's
 * terminal UI and retain its command-understanding/confirmation flow. */
@Keep
class ShizukuCommandUserService : IShizukuCommandService.Stub() {
    override fun destroy() {
        // Shizuku's unbind(remove = true) calls this reserved AIDL transaction.
        Runtime.getRuntime().exit(0)
    }

    override fun execute(command: String?, timeoutSeconds: Int): String {
        val request = command?.trim().orEmpty()
        if (request.isBlank() || request.length > MAX_COMMAND_LENGTH || request.contains('\u0000')) {
            return "[ERROR] Empty or oversized Shizuku command."
        }
        TerminalCommandPolicy.refusalReason(request)?.let { reason -> return "[BLOCKED] $reason" }

        var process: Process? = null
        val output = ByteArrayOutputStream()
        var truncated = false
        try {
            val started = ProcessBuilder("sh", "-c", request).redirectErrorStream(true).start()
            process = started
            started.outputStream.close()
            val reader = thread(name = "lucifer-shizuku-output", isDaemon = true) {
                val buffer = ByteArray(4096)
                started.inputStream.use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = MAX_OUTPUT_BYTES - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                            if (count > remaining) truncated = true
                        }
                    }
                }
            }

            val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).toLong()
            val completed = started.waitFor(timeout, TimeUnit.SECONDS)
            if (!completed) {
                started.destroyForcibly()
                started.waitFor(2, TimeUnit.SECONDS)
            }
            reader.join(1_000)
            val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
            return buildString {
                if (text.isNotBlank()) appendLine(text.trimEnd())
                if (truncated) appendLine("[INFO] Output truncated at $MAX_OUTPUT_BYTES bytes")
                if (!completed) appendLine("[ERROR] Shizuku command timed out after ${timeout}s")
                else appendLine("[exit ${started.exitValue()}]")
            }.trimEnd()
        } catch (error: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            return "[ERROR] Shizuku command interrupted."
        } catch (error: Exception) {
            process?.destroyForcibly()
            return "[ERROR] Shizuku command failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private companion object {
        const val MAX_COMMAND_LENGTH = 4_000
        const val MAX_OUTPUT_BYTES = 32_000
        const val MAX_TIMEOUT_SECONDS = 60
    }
}
