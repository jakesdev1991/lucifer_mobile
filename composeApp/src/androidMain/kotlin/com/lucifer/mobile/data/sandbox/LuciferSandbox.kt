package com.lucifer.mobile.data.sandbox

import android.content.Context
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiSandboxBridge
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuciferSandbox {

    private val kaiBridge: KaiSandboxBridge
    private val scope = CoroutineScope(Dispatchers.IO)

    constructor(context: Context) {
        val bridge = KaiBridge(context)
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

    suspend fun execPython(script: String, args: List<String> = emptyList()): SandboxResult {
        val result = kaiBridge.execPython(script, args)
        return SandboxResult(
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode
        )
    }

    suspend fun execNode(script: String, args: List<String> = emptyList()): SandboxResult {
        val result = kaiBridge.execNode(script, args)
        return SandboxResult(
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode
        )
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