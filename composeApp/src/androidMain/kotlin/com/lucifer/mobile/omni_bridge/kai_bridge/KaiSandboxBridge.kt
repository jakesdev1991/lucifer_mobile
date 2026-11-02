package com.lucifer.mobile.omni_bridge.kai_bridge

/**
 * Sandbox Bridge — real `run-as` execution inside the Kai package, with honest failures.
 *
 * Every command goes through [KaiBridge.execute], which records the attempt in the egress ledger
 * (a sandbox command can reach the network even though the bridge itself opens no socket) and
 * reports the exact run-as refusal when the OS blocks it. Non-read-only commands must have passed
 * the caller's confirmation flow before they reach this class.
 */
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
        } ?: SandboxResult(exitCode = -1, stdout = "", stderr = "run-as unavailable", success = false)
    }

    suspend fun execPython(script: String, args: List<String> = emptyList()): SandboxResult {
        return execute("python3", listOf("-c", script) + args)
    }

    suspend fun execNode(script: String, args: List<String> = emptyList()): SandboxResult {
        return execute("node", listOf("-e", script) + args)
    }

    suspend fun evaluateRcod(stateVectors: List<FloatArray>): RcodResult {
        // RCOD "phase-transition detection" claimed a state vector nothing in this app produces
        // (CAPABILITIES.md §9). It is not re-advertised; the honest result is a refusal.
        return RcodResult(
            muValues = emptyList(),
            metrics = "",
            success = false
        )
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
