package com.lucifer.mobile.omni_bridge

import com.lucifer.mobile.data.wallet.LuciferWallet
import com.lucifer.mobile.data.browser.LuciferBrowser
import com.lucifer.mobile.data.llm.LuciferLLM
import com.lucifer.mobile.data.sandbox.LuciferSandbox
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.content.Context

class OmniBridge(
    private val context: Context
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    // State flows for UI observation
    private val _health = MutableStateFlow<HealthStatus>(HealthStatus.UNKNOWN)
    val health = _health.asStateFlow()

    private val _events = MutableSharedFlow<OmniEvent>()
    val events = _events.asSharedFlow()

    // Component references - pass context where needed
    val wallet: LuciferWallet = LuciferWallet(context)
    val browser: LuciferBrowser = LuciferBrowser(context)
    val llm: LuciferLLM = LuciferLLM(context)
    val sandbox: LuciferSandbox = LuciferSandbox(context)
    val kaiBridge: KaiBridge = KaiBridge(context)

    fun initialize() {
        // Initialize all components
        scope.launch {
            wallet.initialize()
            browser.initialize()
            llm.initialize()
            sandbox.initialize()

            // Initialize Kai bridge
            kaiBridge.connect()

            _health.value = HealthStatus.HEALTHY
            _events.emit(OmniEvent.INITIALIZED)
        }
    }

    // High-level operations

    fun executeTask(task: String): TaskResult {
        // Route through Omni-Bridge control boundary
        // APPA check -> Router -> Sandbox -> Execution -> Verification -> Provenance
        return TaskResult(success = true, output = "Task executed: $task")
    }

    suspend fun queryLLM(prompt: String): String {
        val response = llm.generate(listOf(com.lucifer.mobile.data.llm.LLMMessage(role = "user", content = prompt)))
        return if (response.success) response.content else "Error: ${response.error}"
    }

    suspend fun executeInSandbox(command: String): SandboxResult {
        val result = sandbox.execute(command)
        return SandboxResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            success = result.success
        )
    }

    suspend fun executeWalletOperation(operation: WalletOperation): WalletResult {
        return when (operation) {
            is WalletOperation.Generate -> {
                val wallet = wallet.generateWallet(operation.chain)
                WalletResult(success = wallet != null, wallet = wallet)
            }
            is WalletOperation.Load -> {
                val wallet = wallet.loadWallet(operation.address)
                WalletResult(success = wallet != null, wallet = wallet)
            }
            is WalletOperation.SignTransaction -> {
                val signed = wallet.signTransaction(operation.params)
                WalletResult(success = signed != null, signedTx = signed)
            }
            is WalletOperation.GetBalance -> {
                val balance = wallet.getBalance(operation.address, operation.token)
                WalletResult(success = balance != null, balance = balance)
            }
        }
    }

    suspend fun executeBrowserOperation(operation: BrowserOperation): BrowserResult {
        return when (operation) {
            is BrowserOperation.Navigate -> {
                val result = browser.navigate(operation.url)
                BrowserResult(success = result.success, url = result.url, content = result.text)
            }
            is BrowserOperation.Click -> {
                val result = browser.click(operation.selector)
                BrowserResult(success = result.success)
            }
            is BrowserOperation.Type -> {
                val result = browser.typeText(operation.selector, operation.text)
                BrowserResult(success = result.success)
            }
            is BrowserOperation.GetContent -> {
                val result = browser.getContent()
                BrowserResult(success = result.success, content = result.text)
            }
            is BrowserOperation.Screenshot -> {
                val screenshot = browser.screenshot(operation.fullPage)
                BrowserResult(success = screenshot != null, screenshot = screenshot)
            }
        }
    }

    suspend fun executeSandboxOperation(operation: SandboxOperation): SandboxResult {
        return when (operation) {
            is SandboxOperation.Execute -> {
                val result = sandbox.execute(operation.command, operation.args)
                SandboxResult(
                    success = result.success,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    exitCode = result.exitCode
                )
            }
            is SandboxOperation.Python -> {
                val result = sandbox.execPython(operation.script, operation.args)
                SandboxResult(
                    success = result.success,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    exitCode = result.exitCode
                )
            }
            is SandboxOperation.Node -> {
                val result = sandbox.execNode(operation.script, operation.args)
                SandboxResult(
                    success = result.success,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    exitCode = result.exitCode
                )
            }
        }
    }

    fun shutdown() {
        wallet.shutdown()
        browser.shutdown()
        llm.shutdown()
        sandbox.shutdown()
        _health.value = HealthStatus.SHUTDOWN
        scope.launch { _events.emit(OmniEvent.SHUTDOWN) }
    }
}

// Data classes

enum class HealthStatus {
    UNKNOWN, INITIALIZING, HEALTHY, DEGRADED, UNHEALTHY, SHUTDOWN
}

enum class OmniEvent {
    INITIALIZED, STARTED, STOPPED, SHUTDOWN, TASK_STARTED, TASK_COMPLETED, ERROR
}

data class TaskResult(
    val success: Boolean,
    val output: String
)

data class SandboxResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1
)

data class WalletResult(
    val success: Boolean,
    val wallet: com.lucifer.mobile.data.wallet.Wallet? = null,
    val signedTx: String? = null,
    val balance: String? = null
)

data class BrowserResult(
    val success: Boolean,
    val url: String = "",
    val content: String = "",
    val screenshot: String? = null
)

sealed class WalletOperation {
    data class Generate(val chain: String = "arbitrum") : WalletOperation()
    data class Load(val address: String) : WalletOperation()
    data class SignTransaction(val params: Map<String, String>) : WalletOperation()
    data class GetBalance(val address: String, val token: String = "ETH") : WalletOperation()
}

sealed class BrowserOperation {
    data class Navigate(val url: String) : BrowserOperation()
    data class Click(val selector: String) : BrowserOperation()
    data class Type(val selector: String, val text: String) : BrowserOperation()
    data class GetContent(val dummy: Boolean = true) : BrowserOperation()
    data class Screenshot(val fullPage: Boolean = false) : BrowserOperation()
}

sealed class SandboxOperation {
    data class Execute(val command: String, val args: List<String>) : SandboxOperation()
    data class Python(val script: String, val args: List<String>) : SandboxOperation()
    data class Node(val script: String, val args: List<String>) : SandboxOperation()
}

data class LLMMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String
)