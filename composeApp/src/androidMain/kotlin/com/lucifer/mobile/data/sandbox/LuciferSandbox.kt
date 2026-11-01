package com.lucifer.mobile.data.sandbox

import android.content.Context
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiResponse
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiSandboxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LuciferSandbox — execution inside the Kai package via `run-as`.
 *
 * Every command goes through [KaiBridge.execute], which records the attempt in the egress ledger and
 * reports the exact run-as refusal when the OS blocks it. [probe] is the read-only entry point; any
 * non-read-only use must have passed the caller's confirmation flow first.
 */
class LuciferSandbox {

    private val bridge: KaiBridge
    private val kaiBridge: KaiSandboxBridge
    private val scope = CoroutineScope(Dispatchers.IO)

    constructor(context: Context) {
        this.bridge = KaiBridge(context)
        this.kaiBridge = KaiSandboxBridge(bridge)
    }

    fun initialize() {
        scope.launch {
            kaiBridge.initialize()
        }
    }

    suspend fun execute(command: String, args: List<String> = emptyList()): SandboxResult {
        val result = kaiBridge.execute(command, args)
        return SandboxResult(
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode
        )
    }

    suspend fun execPython(script: String, args: List<String> = emptyList()): SandboxResult =
        execute("python3", listOf("-c", script) + args)

    suspend fun execNode(script: String, args: List<String> = emptyList()): SandboxResult =
        execute("node", listOf("-e", script) + args)

    /** Read-only probe; never recorded as network-touching. */
    suspend fun probe(command: String, timeoutMs: Int = 10_000): SandboxResult? =
        withContext(Dispatchers.IO) {
            when (val r = bridge.execReadOnly(command, timeoutMs)) {
                is KaiResponse.SandboxResult -> SandboxResult(
                    success = r.exitCode == 0,
                    stdout = r.stdout,
                    stderr = r.stderr,
                    exitCode = r.exitCode
                )
                is KaiResponse.Error -> SandboxResult(
                    success = false,
                    stdout = "",
                    stderr = r.message,
                    exitCode = -1
                )
                else -> null
            }
        }

    suspend fun evaluateRcod(stateVectors: List<FloatArray>): RcodResult {
        val result = kaiBridge.evaluateRcod(stateVectors)
        return RcodResult(
            muValues = result.muValues,
            metrics = result.metrics,
            success = result.success
        )
    }

    fun shutdown() {
        kaiBridge.shutdown()
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
