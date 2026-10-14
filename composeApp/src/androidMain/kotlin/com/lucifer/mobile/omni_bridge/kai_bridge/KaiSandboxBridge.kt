package com.lucifer.mobile.omni_bridge.kai_bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Sandbox Bridge - simplified interface for sandbox operations
class KaiSandboxBridge(
    private val kaiBridge: KaiBridge
) {

    suspend fun initialize() {
        kaiBridge.connect()
    }

    suspend fun execute(command: String, args: List<String> = emptyList()): SandboxResult {
        val fullCommand = if (args.isEmpty()) command else "$command ${args.joinToString(" ")}"
        val result = kaiBridge.execute(fullCommand)
        return result?.let {
            SandboxResult(
                exitCode = it.exitCode,
                stdout = it.stdout,
                stderr = it.stderr,
                success = it.exitCode == 0
            )
        } ?: SandboxResult(exitCode = -1, stdout = "", stderr = "Not connected", success = false)
    }

    suspend fun execPython(script: String, args: List<String> = emptyList()): SandboxResult {
        return execute("python3", listOf("-c", script) + args)
    }

    suspend fun execNode(script: String, args: List<String> = emptyList()): SandboxResult {
        return execute("node", listOf("-e", script) + args)
    }

    suspend fun evaluateRcod(stateVectors: List<FloatArray>): RcodResult {
        val result = kaiBridge.evaluateRcod(stateVectors)
        return result?.let {
            RcodResult(
                muValues = it.muValues,
                metrics = it.metrics,
                success = true
            )
        } ?: RcodResult(muValues = emptyList(), metrics = "", success = false)
    }

    fun shutdown() {
        kaiBridge.disconnect()
    }
}

data class SandboxResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1
)

data class RcodResult(
    val muValues: List<Float>,
    val metrics: String,
    val success: Boolean
)