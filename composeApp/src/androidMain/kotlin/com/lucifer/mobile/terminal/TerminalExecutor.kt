package com.lucifer.mobile.terminal

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect

class TerminalExecutor(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "TerminalExecutor"
        private const val LOCAL = 0
        private const val TERMUX = 1
        private const val VM = 2
    }

    suspend fun execute(command: String, target: Int): List<String> = withContext(Dispatchers.IO) {
        return@withContext when (target) {
            LOCAL -> executeLocal(command)
            TERMUX -> executeTermux(command)
            VM -> executeVm(command)
            else -> listOf("[ERROR] Unknown target: $target")
        }
    }

    private fun executeLocal(command: String): List<String> {
        val output = mutableListOf<String>()
        try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val process = pb.start()

            // Read output with timeout
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            val startTime = System.currentTimeMillis()
            while (reader.readLine().also { line = it } != null) {
                if (System.currentTimeMillis() - startTime > 10000) {
                    process.destroyForcibly()
                    output.add("[ERROR] Command timed out after 10s")
                    break
                }
                output.add(line ?: "")
            }
            process.waitFor()
            output.add("[exit ${process.exitValue()}]")
        } catch (e: Exception) {
            output.add("[ERROR] ${e.message}")
        }
        if (output.size == 1) output.add(0, "[INFO] app sandbox — no root")
        return output
    }

    private fun executeTermux(command: String): List<String> {
        val output = mutableListOf<String>()
        try {
            val intent = Intent("com.termux.app.RUN_COMMAND")
            intent.setClassName("com.termux.app", "com.termux.app.RunCommandService")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            intent.putExtra("com.termux.RUN_COMMAND_WORKING_DIRECTORY", "/data/data/com.termux/files/home")
            intent.putExtra("com.termux.RUN_COMMAND_EXECUTE_IN_BACKGROUND", true)
            
            val result = context.startService(intent)
            if (result == null) {
                throw Exception("Termux service not found or not running")
            }
            output.add("[INFO] Command sent to Termux (background execution)")
            output.add("[exit 0]")
        } catch (e: Exception) {
            output.add("[ERROR] Termux rejected command. Open Termux once and run: mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings")
            output.add("[ERROR] ${e.message}")
        }
        return output
    }

    private fun executeVm(command: String): List<String> {
        val output = mutableListOf<String>()
        val password = loadVmPassword()

        if (password.isNullOrBlank()) {
            output.add("[ERROR] Set VM SSH password in Settings first")
            return output
        }

        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val jsch = JSch()
            // VM host is configurable (Settings → VM HOST). The AVF bridge IP
            // changes per VM reinstall — never hardcode it.
            session = jsch.getSession("droid", vmHost(), vmPort())
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password")
            session.connect(5000)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setErrStream(java.io.ByteArrayOutputStream()) // discard stderr (merged reading of inputStream covers both in practice)
            channel.connect()

            val inputStream = channel.inputStream
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                val chunk = String(buffer, 0, bytesRead)
                chunk.lines().forEach { line ->
                    if (line.isNotBlank()) output.add(line)
                }
            }

            // Wait for channel to close (poll until closed)
            val startWait = System.currentTimeMillis()
            while (!channel.isClosed && System.currentTimeMillis() - startWait < 10000) {
                Thread.sleep(50)
            }
            output.add("[exit ${channel.exitStatus}]")
        } catch (e: Exception) {
            output.add("[ERROR] Debian VM not reachable at ${vmHost()}:${vmPort()} — set the VM HOST in Settings (the AVF bridge IP changes per VM reinstall), check the Terminal app's Port control allows 8022, and run enable_ssh_vm.sh inside the VM console (scripts staged at /sdcard/ashigaru/).")
            output.add("[ERROR] ${e.message}")
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
        return output
    }

    private fun loadVmPassword(): String? {
        return try {
            val file = File(context.filesDir, "config/vm_ssh_password.txt")
            if (file.exists()) {
                FileInputStream(file).use { input ->
                    input.readBytes().decodeToString().trim()
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** VM SSH host — configurable (Settings → VM HOST); empty = not set. */
    private fun vmHost(): String {
        return try {
            val file = File(context.filesDir, "config/vm_host.txt")
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** VM SSH port — configurable; default 8022 (AVF Terminal port-forward). */
    private fun vmPort(): Int {
        return try {
            val file = File(context.filesDir, "config/vm_port.txt")
            if (file.exists()) file.readText().trim().toIntOrNull() ?: 8022 else 8022
        } catch (e: Exception) {
            8022
        }
    }
}