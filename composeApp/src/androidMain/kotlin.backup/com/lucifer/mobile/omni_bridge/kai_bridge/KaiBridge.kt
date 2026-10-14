package com.lucifer.mobile.omni_bridge.kai_bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.MutableStateFlow
import kotlinx.coroutines.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.tryEmit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import android.content.Context
import com.lucifer.mobile.data.wallet.WalletKeypair
import com.lucifer.mobile.data.browser.BrowserResult
import com.lucifer.mobile.data.browser.ClickResult
import com.lucifer.mobile.data.browser.TypeResult
import com.lucifer.mobile.data.browser.EvaluateResult
import com.lucifer.mobile.data.sandbox.SandboxResult
import com.lucifer.mobile.data.sandbox.ExecResult
import com.lucifer.mobile.data.llm.LLMResponse
import com.lucifer.mobile.data.llm.LLMMessage

class KaiBridge {
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val _health = MutableStateFlow<HealthStatus>(HealthStatus.UNKNOWN)
    val health = _health.asStateFlow()
    
    private val _events = MutableSharedFlow<KaiEvent>()
    val events = _events
    
    companion object {
        @JvmStatic
        lateinit var instance: KaiBridge
    }
    
    fun initialize() {
        _health.value = HealthStatus.HEALTHY
        emitEvent(KaiEvent.INITIALIZED)
    }
    
    suspend fun generateWallet(chain: String = "arbitrum"): WalletKeypair? {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "generate", "chain": "$chain"}
                """.trimIndent()
                val result = executePython("wallet_ops.py", json)
                if (result.success) {
                    val jsonResult = kotlinx.serialization.json.Json.decodeFromString<WalletKeypair>(result.stdout)
                    jsonResult
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    suspend fun loadWallet(address: String): WalletKeypair? {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "load", "address": "$address"}
                """.trimIndent()
                val result = executePython("wallet_ops.py", json)
                if (result.success) {
                    val jsonResult = kotlinx.serialization.json.Json.decodeFromString<WalletKeypair>(result.stdout)
                    jsonResult
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    suspend fun signTransaction(txParams: Map<String, String>): String? {
        return withContext(Dispatchers.IO) {
            try {
                val json = kotlinx.serialization.json.Json.encodeToString(txParams)
                val result = executePython("wallet_ops.py", json)
                if (result.success) {
                    result.stdout.trim()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    suspend fun getBalance(address: String, token: String = "ETH"): String? {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "balance", "address": "$address", "token": "$token"}
                """.trimIndent()
                val result = executePython("wallet_ops.py", json)
                if (result.success) {
                    result.stdout.trim()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    suspend fun navigate(url: String): BrowserResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "navigate", "url": "$url"}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<BrowserResult>(result.stdout)
                } else {
                    BrowserResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                BrowserResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun click(selector: String): ClickResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "click", "selector": "$selector"}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<ClickResult>(result.stdout)
                } else {
                    ClickResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                ClickResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun typeText(selector: String, text: String): TypeResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "type", "selector": "$selector", "text": "$text"}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<TypeResult>(result.stdout)
                } else {
                    TypeResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                TypeResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun getContent(): BrowserResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "content"}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<BrowserResult>(result.stdout)
                } else {
                    BrowserResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                BrowserResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun evaluate(script: String): EvaluateResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "evaluate", "script": "$script"}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<EvaluateResult>(result.stdout)
                } else {
                    EvaluateResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                EvaluateResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun screenshot(fullPage: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "screenshot", "full_page": $fullPage}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    result.stdout.trim()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    suspend fun getCookies(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "cookies"}
                """.trimIndent()
                val result = executePython("browser_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(result.stdout)
                } else {
                    emptyMap()
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
    
    suspend fun setCookies(cookies: Map<String, String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = kotlinx.serialization.json.Json.encodeToString(cookies)
                val fullJson = """
                    {"action": "set_cookies", "cookies": $json}
                """.trimIndent()
                val result = executePython("browser_ops.py", fullJson)
                result.success
            } catch (e: Exception) {
                false
            }
        }
    }
    
    suspend fun execute(command: String, args: List<String> = emptyList()): ExecResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "execute", "command": "$command", "args": ${kotlinx.serialization.json.Json.encodeToString(args)}}
                """.trimIndent()
                val result = executePython("sandbox_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<ExecResult>(result.stdout)
                } else {
                    ExecResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                ExecResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun execPython(script: String, args: List<String> = emptyList()): ExecResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "python", "script": "$script", "args": ${kotlinx.serialization.json.Json.encodeToString(args)}}
                """.trimIndent()
                val result = executePython("sandbox_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<ExecResult>(result.stdout)
                } else {
                    ExecResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                ExecResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun execNode(script: String, args: List<String> = emptyList()): ExecResult {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "node", "script": "$script", "args": ${kotlinx.serialization.json.Json.encodeToString(args)}}
                """.trimIndent()
                val result = executePython("sandbox_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<ExecResult>(result.stdout)
                } else {
                    ExecResult(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                ExecResult(success = false, error = e.message)
            }
        }
    }
    
    suspend fun generate(messages: List<LLMMessage>): LLMResponse {
        return withContext(Dispatchers.IO) {
            try {
                val json = kotlinx.serialization.json.Json.encodeToString(messages)
                val fullJson = """
                    {"action": "generate", "messages": $json}
                """.trimIndent()
                val result = executePython("llm_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<LLMResponse>(result.stdout)
                } else {
                    LLMResponse(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                LLMResponse(success = false, error = e.message)
            }
        }
    }
    
    suspend fun generateWithTools(messages: List<LLMMessage>, tools: List<Map<String, Any>>): LLMResponse {
        return withContext(Dispatchers.IO) {
            try {
                val messagesJson = kotlinx.serialization.json.Json.encodeToString(messages)
                val toolsJson = kotlinx.serialization.json.Json.encodeToString(tools)
                val fullJson = """
                    {"action": "generate_with_tools", "messages": $messagesJson, "tools": $toolsJson}
                """.trimIndent()
                val result = executePython("llm_ops.py", fullJson)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<LLMResponse>(result.stdout)
                } else {
                    LLMResponse(success = false, error = result.stderr)
                }
            } catch (e: Exception) {
                LLMResponse(success = false, error = e.message)
            }
        }
    }
    
    suspend fun listModels(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "list_models"}
                """.trimIndent()
                val result = executePython("llm_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(result.stdout)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    suspend fun checkHealth(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "health"}
                """.trimIndent()
                val result = executePython("llm_ops.py", json)
                result.success
            } catch (e: Exception) {
                false
            }
        }
    }
    
    suspend fun rotateKey() {
        withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "rotate_key"}
                """.trimIndent()
                executePython("llm_ops.py", json)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    suspend fun getProviderStatus(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                val json = """
                    {"action": "provider_status"}
                """.trimIndent()
                val result = executePython("llm_ops.py", json)
                if (result.success) {
                    kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(result.stdout)
                } else {
                    emptyMap()
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
    
    suspend fun setProviderConfig(config: Map<String, Any>) {
        withContext(Dispatchers.IO) {
            try {
                val json = kotlinx.serialization.json.Json.encodeToString(config)
                val fullJson = """
                    {"action": "set_provider_config", "config": $json}
                """.trimIndent()
                executePython("llm_ops.py", fullJson)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private suspend fun executePython(script: String, json: String): ExecResult {
        return withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("python3", "/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/omni_bridge/$script", json).start()
                proc.outputStream.use { it.write(json.toByteArray()) }
                val stdout = proc.inputStream.readBytes().decodeToString()
                val stderr = proc.errorStream.readBytes().decodeToString()
                val exitCode = proc.waitFor()
                ExecResult(exitCode == 0, stdout.trim(), stderr.trim(), exitCode)
            } catch (e: Exception) {
                ExecResult(false, "", e.message ?: "", -1)
            }
        }
    }
    
    private fun emitEvent(event: KaiEvent) {
        scope.launch { _events.tryEmit(event) }
    }
}

enum class HealthStatus { UNKNOWN, INITIALIZING, HEALTHY, DEGRADED, UNHEALTHY, SHUTDOWN }
enum class KaiEvent { INITIALIZED, STARTED, STOPPED, SHUTDOWN, TASK_STARTED, TASK_COMPLETED, ERROR }

data class ExecResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1
)