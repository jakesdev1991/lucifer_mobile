package com.lucifer.mobile.omni_bridge.kai_bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Message types for Kai sandbox communication
sealed class KaiMessage {
    data class WalletGenerate(val chain: String, val count: Int = 1) : KaiMessage()
    data class WalletSign(val address: String, val data: String, val derivationPath: String? = null) : KaiMessage()
    data class WalletBalance(val address: String, val chain: String) : KaiMessage()
    data class BrowserNavigate(val url: String, val waitUntil: String = "networkidle") : KaiMessage()
    data class BrowserClick(val selector: String) : KaiMessage()
    data class BrowserScreenshot(val fullPage: Boolean = false) : KaiMessage()
    data class BrowserEvaluate(val script: String) : KaiMessage()
    data class LlmQuery(val prompt: String, val model: String = "nemotron-3-ultra", val maxTokens: Int = 2048) : KaiMessage()
    data class SandboxExec(val command: String, val timeoutMs: Int = 30000) : KaiMessage()
    data class RcodEvaluate(val stateVectors: List<FloatArray>) : KaiMessage()
    object Ping : KaiMessage()
}

// Response types
sealed class KaiResponse {
    data class WalletGenerated(val addresses: List<String>, val privateKeys: List<String>) : KaiResponse()
    data class WalletSigned(val signature: String, val signedData: String) : KaiResponse()
    data class WalletBalances(val balances: Map<String, String>) : KaiResponse()
    data class BrowserResult(val success: Boolean, val data: String, val error: String?) : KaiResponse()
    data class LlmResponse(val text: String, val tokensUsed: Int, val latencyMs: Int) : KaiResponse()
    data class SandboxResult(val exitCode: Int, val stdout: String, val stderr: String) : KaiResponse()
    data class RcodResult(val muValues: List<Float>, val metrics: String) : KaiResponse()
    data class Error(val message: String) : KaiResponse()
    object Pong : KaiResponse()
}

// Kai Bridge - executes via sandbox (no JNI needed)
class KaiBridge(private val context: Context) {

    companion object {
        private const val MAX_CAPTURE_CHARS = 1_000_000
    }

    private var connected = false

    // Initialize connection to Kai sandbox
    suspend fun connect(): Boolean {
        // Test sandbox connectivity
        return try {
            val result = execute("echo 'test'", 5000)
            connected = result?.exitCode == 0
            connected
        } catch (e: Exception) {
            connected = false
            false
        }
    }

    // Wallet operations - executed via sandbox Python scripts
    suspend fun generateWallet(chain: String = "arbitrum", count: Int = 1): KaiResponse.WalletGenerated? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/wallet')
            from wallet.generate import generate_wallet
            result = generate_wallet(chain=${pythonLiteral(chain)}, count=${count.coerceIn(1, 10)})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 10000)
        return parseWalletGenerated(result?.stdout ?: "")
    }

    suspend fun signTransaction(address: String, data: String, derivationPath: String? = null): KaiResponse.WalletSigned? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/wallet')
            from wallet.sign import sign_transaction
            result = sign_transaction(address=${pythonLiteral(address)}, data=${pythonLiteral(data)}, derivation_path=${pythonLiteral(derivationPath.orEmpty())})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 10000)
        return parseWalletSigned(result?.stdout ?: "")
    }

    suspend fun getBalance(address: String, chain: String): KaiResponse.WalletBalances? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/wallet')
            from wallet.balance import get_balance
            result = get_balance(address=${pythonLiteral(address)}, chain=${pythonLiteral(chain)})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 15000)
        return parseWalletBalances(result?.stdout ?: "")
    }

    // Browser automation - executed via sandbox Python/Playwright
    suspend fun navigate(url: String, waitUntil: String = "networkidle"): KaiResponse.BrowserResult? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/browser')
            from browser.navigate import navigate
            result = navigate(url=${pythonLiteral(url)}, wait_until=${pythonLiteral(waitUntil)})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 30000)
        return parseBrowserResult(result?.stdout ?: "")
    }

    suspend fun click(selector: String): KaiResponse.BrowserResult? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/browser')
            from browser.click import click
            result = click(selector=${pythonLiteral(selector)})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 15000)
        return parseBrowserResult(result?.stdout ?: "")
    }

    suspend fun screenshot(fullPage: Boolean = false): KaiResponse.BrowserResult? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/browser')
            from browser.screenshot import screenshot
            result = screenshot(full_page=$fullPage)
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 15000)
        return parseBrowserResult(result?.stdout ?: "")
    }

    suspend fun evaluate(script: String): KaiResponse.BrowserResult? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/browser')
            from browser.evaluate import evaluate
            result = evaluate(script=${pythonLiteral(script)})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 15000)
        return parseBrowserResult(result?.stdout ?: "")
    }

    // LLM queries - executed via sandbox with NVIDIA API
    suspend fun queryLlm(prompt: String, model: String = "nemotron-3-ultra", maxTokens: Int = 2048): KaiResponse.LlmResponse? {
        if (!connected) return null
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/llm')
            from llm.query import query_llm
            result = query_llm(prompt=${pythonLiteral(prompt)}, model=${pythonLiteral(model)}, max_tokens=${maxTokens.coerceIn(1, 8192)})
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 60000)
        return parseLlmResponse(result?.stdout ?: "")
    }

    // Sandbox execution - command is intentionally a shell command, but never re-parsed by a parent shell.
    suspend fun execute(command: String, timeoutMs: Int = 30000): KaiResponse.SandboxResult? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val process = Runtime.getRuntime().exec(
                arrayOf("run-as", "com.inspiredandroid.kai", "sh", "-c", command)
            )
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutReader = drainStream(process.inputStream, stdout, "kai-stdout")
            val stderrReader = drainStream(process.errorStream, stderr, "kai-stderr")
            val finished = process.waitFor(timeoutMs.coerceIn(1, 120_000).toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
            }
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            KaiResponse.SandboxResult(
                exitCode = if (finished) process.exitValue() else 124,
                stdout = synchronized(stdout) { stdout.toString() },
                stderr = synchronized(stderr) {
                    stderr.toString() + if (finished) "" else "\n[timeout] sandbox command exceeded its time limit"
                }
            )
        }
    }

    private fun pythonLiteral(value: String): String = JSONObject.quote(value)

    private fun drainStream(stream: java.io.InputStream, output: StringBuilder, name: String) =
        thread(name = name, isDaemon = true) {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length < MAX_CAPTURE_CHARS) output.append(line).append('\n')
                    }
                }
            }
        }

    // RCOD evaluation
    suspend fun evaluateRcod(stateVectors: List<FloatArray>): KaiResponse.RcodResult? {
        if (!connected) return null
        val vectorsJson = stateVectors.joinToString(",") { arr ->
            "[${arr.joinToString(",") { it.toString() }}]"
        }
        val script = """
            import json, sys
            sys.path.insert(0, '/tmp/omni_bridge/sandbox')
            from sandbox.rcod import evaluate_rcod
            result = evaluate_rcod(state_vectors=$vectorsJson)
            print(json.dumps(result))
        """.trimIndent()
        val result = execute(script, 10000)
        return parseRcodResult(result?.stdout ?: "")
    }

    suspend fun ping(): Boolean {
        if (!connected) return false
        val result = execute("echo pong", 5000)
        return result?.stdout?.trim() == "pong"
    }

    fun disconnect() {
        connected = false
    }

    fun isConnected(): Boolean = connected

    // ============ Response Parsers ============

    private fun parseWalletGenerated(json: String): KaiResponse.WalletGenerated? {
        return try {
            val addresses = extractJsonArray(json, "addresses")
            val privateKeys = extractJsonArray(json, "private_keys")
            if (addresses.isNotEmpty()) KaiResponse.WalletGenerated(addresses, privateKeys) else null
        } catch (e: Exception) { null }
    }

    private fun parseWalletSigned(json: String): KaiResponse.WalletSigned? {
        return try {
            val signature = extractJsonString(json, "signature")
            val signedData = extractJsonString(json, "signed_data")
            if (signature.isNotEmpty()) KaiResponse.WalletSigned(signature, signedData) else null
        } catch (e: Exception) { null }
    }

    private fun parseWalletBalances(json: String): KaiResponse.WalletBalances? {
        return try {
            val balances = extractJsonObject(json, "balances")
            if (balances.isNotEmpty()) KaiResponse.WalletBalances(balances) else null
        } catch (e: Exception) { null }
    }

    private fun parseBrowserResult(json: String): KaiResponse.BrowserResult? {
        return try {
            val success = extractJsonBoolean(json, "success")
            val data = extractJsonString(json, "data")
            val error = extractJsonString(json, "error")
            KaiResponse.BrowserResult(success, data, error.takeIf { it.isNotEmpty() })
        } catch (e: Exception) { null }
    }

    private fun parseLlmResponse(json: String): KaiResponse.LlmResponse? {
        return try {
            val text = extractJsonString(json, "text")
            val tokensUsed = extractJsonInt(json, "tokens_used")
            val latencyMs = extractJsonInt(json, "latency_ms")
            if (text.isNotEmpty()) KaiResponse.LlmResponse(text, tokensUsed, latencyMs) else null
        } catch (e: Exception) { null }
    }

    private fun parseRcodResult(json: String): KaiResponse.RcodResult? {
        return try {
            val muValues = extractJsonArray(json, "mu_values").map { it.toFloat() }
            val metrics = extractJsonString(json, "metrics")
            KaiResponse.RcodResult(muValues, metrics)
        } catch (e: Exception) { null }
    }

    // ============ JSON Helpers ============

    private fun extractJsonString(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJsonInt(json: String, key: String): Int {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractJsonBoolean(json: String, key: String): Boolean {
        val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toBoolean() ?: false
    }

    private fun extractJsonArray(json: String, key: String): List<String> {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json)?.groupValues?.get(1) ?: ""
        return match.split(",").map { it.trim().replace("\"", "") }.filter { it.isNotEmpty() }
    }

    private fun extractJsonObject(json: String, key: String): Map<String, String> {
        val pattern = "\"$key\"\\s*:\\s*\\{([^\\}]*)\\}".toRegex()
        val match = pattern.find(json)?.groupValues?.get(1) ?: ""
        val map = mutableMapOf<String, String>()
        for (pair in match.split(",")) {
            val kv = pair.split(":").map { it.trim().replace("\"", "") }
            if (kv.size == 2) map[kv[0]] = kv[1]
        }
        return map
    }
}

// Wallet Bridge - simplified interface for wallet operations
class KaiWalletBridge(
    private val kaiBridge: KaiBridge
) {

    suspend fun initialize() {
        kaiBridge.connect()
    }

    suspend fun generateWallet(chain: String): WalletKeypair? {
        val result = kaiBridge.generateWallet(chain, 1)
        return result?.let { WalletKeypair(it.addresses.first(), it.privateKeys.first(), chain) }
    }

    suspend fun loadWallet(address: String): WalletKeypair? {
        // In production: load from keystore
        return null
    }

    suspend fun signTransaction(txParams: Map<String, String>): String? {
        // In production: sign via Kai sandbox
        return null
    }

    suspend fun getBalance(address: String, token: String): String? {
        val result = kaiBridge.getBalance(address, "ethereum")
        return result?.balances?.get(token)
    }

    fun shutdown() {
        kaiBridge.disconnect()
    }
}

data class WalletKeypair(
    val address: String,
    val privateKey: String,
    val chain: String
)

// Wallet data models for UI
data class WalletInfo(
    val address: String,
    val chain: String,
    val balance: String,
    val symbol: String,
    val usdValue: String,
    val derivationPath: String?
)

data class BrowserSession(
    val url: String,
    val title: String,
    val screenshot: String?,  // Base64 encoded
    val timestamp: Long
)

data class LlmConversation(
    val id: String,
    val messages: List<LlmMessage>,
    val model: String,
    val timestamp: Long
)

data class LlmMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long
)