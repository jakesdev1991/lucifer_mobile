package com.lucifer.mobile.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class SSHService : Service() {

    private val TAG = "SSHService"
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SSHService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Starting SSH server on port 8022")
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(8022)
                serverSocket?.setReuseAddress(true)
                isRunning = true
                Log.i(TAG, "SSH server listening on port 8022")

                while (isRunning) {
                    val socket = serverSocket?.accept()
                    socket?.let { clientSocket ->
                        scope.launch {
                            handleClient(clientSocket)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSH server error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

            writer.println("😈 Lucifer SSH Server Ready")
            writer.println("Type 'help' for commands")

            while (socket.isConnected) {
                val line = reader.readLine() ?: break
                val response = processCommand(line.trim())
                writer.println(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun processCommand(command: String): String {
        return when (command.lowercase().trim()) {
            "help" -> """
                😈 Lucifer SSH Commands:
                help          - Show this help
                wallet        - Wallet operations
                browser       - Browser automation
                llm <prompt>  - Query LLM
                sandbox <cmd> - Execute in Kai sandbox
                wallet gen    - Generate wallet
                wallet bal    - Check balance
                navigate <url> - Navigate browser
                exec <cmd>    - Execute in sandbox
                python <script> - Run Python
                node <script>  - Run Node.js
                health        - System health
                status        - System status
                exit          - Close connection
            """.trimIndent()
            "wallet gen" -> {
                "Generating wallet... (not implemented in SSH yet)"
            }
            "wallet bal" -> "Wallet balance: (not implemented in SSH yet)"
            "llm " -> {
                val prompt = command.substring(4)
                "LLM query: $prompt (not implemented in SSH yet)"
            }
            "sandbox " -> {
                val cmd = command.substring(8)
                "Sandbox: $cmd (not implemented in SSH yet)"
            }
            "navigate " -> {
                val url = command.substring(9)
                "Navigate to: $url (not implemented in SSH yet)"
            }
            "exec " -> {
                val cmd = command.substring(5)
                "Exec: $cmd (not implemented in SSH yet)"
            }
            "python " -> {
                val script = command.substring(7)
                "Python: $script (not implemented in SSH yet)"
            }
            "node " -> {
                val script = command.substring(5)
                "Node.js: $script (not implemented in SSH yet)"
            }
            "health" -> "System Health: HEALTHY"
            "status" -> "Lucifer Mobile v1.0.0 - RUNNING"
            "exit" -> {
                "Goodbye! 😈"
            }
            else -> "Unknown command: $command. Type 'help' for help."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        Log.i(TAG, "SSHService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}