package com.lucifer.mobile.verification

import android.content.Context
import android.util.Log
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.privacy.EgressHttp
import com.lucifer.mobile.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL

/**
 * Calls the user's loopback-only, read-only Lean-audit MCP server (FastMCP, pinned Lean 4.32.0)
 * running under Termux on the same device. Exposes the server's tools — lean_check, lean_axioms,
 * lean_audit_corpus, lean_explain_failure, lean_verify_claim, lean_audit_report — to the cognition
 * loop as read-only `lean_audit.*` tools. Every call goes through [EgressHttp], so it lands in the
 * egress ledger like any other network read.
 */
class LeanAuditMcpClient(private val context: Context) {
    companion object {
        private const val TAG = "LeanAuditMCP"
        private val URL_FILE get() = AppConfig.LEAN_AUDIT_MCP_URL
        private val TOKEN_FILE get() = AppConfig.LEAN_AUDIT_MCP_TOKEN
        private const val CONNECT_TIMEOUT_MS = 500
        // Lean compilation is slow; the audit tools can take a while on first run.
        private const val READ_TIMEOUT_MS = 120_000
        private const val PROTOCOL_VERSION = "2025-11-25"
        private const val MAX_RESPONSE_CHARS = 64 * 1024

        val TOOL_NAMES = listOf(
            "lean_check", "lean_axioms", "lean_audit_corpus",
            "lean_explain_failure", "lean_verify_claim", "lean_audit_report"
        )
    }

    /** The MCP tool name as exposed to the cognition loop, or null for unknown names. */
    fun cognitionToolName(tool: String): String? =
        TOOL_NAMES.firstOrNull { "lean_audit.$it" == tool }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val endpoint = readEndpoint() ?: return@withContext "Lean-audit MCP URL is not configured or is not an allowed loopback endpoint."
        val token = readToken() ?: return@withContext "Lean-audit MCP bearer token is not configured."
        try {
            val tools = listTools(endpoint, token)
                ?: return@withContext "No valid MCP tools/list response; check that Termux is running the lean-audit server and the token matches."
            val names = StringBuilder()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                if (names.isNotEmpty()) names.append(", ")
                names.append(tool.optString("name"))
            }
            if (names.isEmpty()) "Connected, but the server exposes no tools." else "Connected · ${tools.length()} tools: $names"
        } catch (error: Exception) {
            "Lean-audit MCP check failed (${error.javaClass.simpleName}); the cognition loop will skip it."
        }
    }

    /**
     * Invoke one server tool. Fail open: returns an error string, never throws, so a stopped
     * Termux server cannot break the cognition loop.
     */
    suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val endpoint = readEndpoint()
            ?: return@withContext "lean-audit MCP is not configured (loopback URL/token missing)."
        val token = readToken()
            ?: return@withContext "lean-audit MCP bearer token is not configured."
        try {
            callToolRaw(endpoint, token, toolName, arguments)
                ?: "lean-audit MCP returned no text for $toolName."
        } catch (error: Exception) {
            Log.i(TAG, "lean-audit tool $toolName skipped (${error.javaClass.simpleName})")
            "lean-audit MCP call failed (${error.javaClass.simpleName}); server may be stopped."
        }
    }

    private fun readEndpoint(): String? {
        val raw = AppConfig.readText(context, URL_FILE) ?: return null
        return validateLoopbackEndpoint(raw)
    }

    private fun readToken(): String? =
        SecretStore.read(context, TOKEN_FILE)?.trim()?.takeIf { it.length >= 24 }

    /** Only the chosen same-device Termux listener is allowed; never forward to arbitrary hosts. */
    fun validateLoopbackEndpoint(raw: String): String? {
        return try {
            val url = URL(raw)
            if (url.protocol != "http" || url.host !in setOf("127.0.0.1", "localhost") ||
                url.port != 8931 || url.path != "/mcp" || url.query != null || url.userInfo != null || url.ref != null
            ) null else raw.trimEnd('/')
        } catch (_: Exception) { null }
    }

    private fun openSession(endpoint: String, token: String): Pair<String, String>? {
        var nextId = 1
        val initialize = post(
            endpoint, token, null, null,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", nextId++)
                .put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "Lucifer Android").put("version", "1.0.0")))
        ) ?: return null
        val sessionId = initialize.sessionId ?: return null
        val initResult = initialize.body?.optJSONObject("result") ?: return null
        val protocolVersion = initResult.optString("protocolVersion", PROTOCOL_VERSION)
        post(
            endpoint, token, protocolVersion, sessionId,
            JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        ) ?: return null
        return protocolVersion to sessionId
    }

    private fun listTools(endpoint: String, token: String): JSONArray? {
        val session = openSession(endpoint, token) ?: return null
        val call = post(
            endpoint, token, session.first, session.second,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 100)
                .put("method", "tools/list")
                .put("params", JSONObject())
        ) ?: return null
        return call.body?.optJSONObject("result")?.optJSONArray("tools")
    }

    private fun callToolRaw(endpoint: String, token: String, toolName: String, arguments: JSONObject): String? {
        val session = openSession(endpoint, token) ?: return null
        val call = post(
            endpoint, token, session.first, session.second,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 101)
                .put("method", "tools/call")
                .put("params", JSONObject()
                    .put("name", toolName)
                    .put("arguments", arguments))
        ) ?: return null
        val result = call.body?.optJSONObject("result") ?: return null
        val content = result.optJSONArray("content") ?: return null
        if (result.optBoolean("isError", false)) {
            val errText = StringBuilder()
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") == "text") {
                    if (errText.isNotEmpty()) errText.append('\n')
                    errText.append(item.optString("text"))
                }
            }
            return "lean-audit tool error: ${errText.toString().ifBlank { "isError" }}"
        }
        val text = StringBuilder()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") {
                if (text.isNotEmpty()) text.append('\n')
                text.append(item.optString("text"))
            }
        }
        return text.toString().takeIf { it.isNotBlank() }
    }

    private data class RpcResponse(val body: JSONObject?, val sessionId: String?)

    private fun post(
        endpoint: String,
        token: String,
        protocolVersion: String?,
        sessionId: String?,
        payload: JSONObject
    ): RpcResponse? {
        // The MCP endpoint is user-editable, so "loopback-only" is the default, not a guarantee.
        // A local URL passes even with the network off; anything remote respects the switch.
        val grant = EgressHttp.open(
            endpoint,
            userInitiated = false,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS
        )
        if (grant.blocked) {
            Log.i(TAG, "lean-audit MCP call skipped: ${grant.decision.reason}")
            return null
        }
        val connection = grant.connection!!
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            protocolVersion?.let { connection.setRequestProperty("MCP-Protocol-Version", it) }
            sessionId?.let { connection.setRequestProperty("MCP-Session-Id", it) }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_CHARS) throw IllegalStateException("MCP response exceeded its size limit")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if (status !in 200..299) return null
            val body = parseJsonRpcBody(responseText)
            RpcResponse(body, connection.getHeaderField("MCP-Session-Id"))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonRpcBody(text: String): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null // notifications/initialized returns HTTP 202 with no body.
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val data = trimmed.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .firstOrNull { it.startsWith("{") }
            ?: return null
        return JSONObject(data)
    }
}
