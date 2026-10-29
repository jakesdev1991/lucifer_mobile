package com.lucifer.mobile.omni_bridge.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import com.lucifer.mobile.privacy.NetworkGuard
import java.net.HttpURLConnection
import java.net.URL

/**
 * OmegaKnowledgeClient — Lucifer's link to his persistent GitHub knowledge.
 *
 * Speaks JSON-RPC 2.0 to the omega-knowledge-mcp server (the ToE corpus:
 * 54 Lean 4 proof volumes, axioms, docs, full book), wherever it runs:
 *   - Termux/device-local server (http://127.0.0.1:8030)
 *   - Emulator host (http://10.0.2.2:8030)
 *   - Remote VM (user-configured HTTPS URL)
 *
 * All calls are suspend + IO dispatcher. Never blocks the UI.
 */
class OmegaKnowledgeClient(
    @Volatile var baseUrl: String = ""
) {

    companion object {
        private const val TAG = "OmegaKnowledge"
        private const val TIMEOUT_MS = 6000
        const val LOCAL_URL = "http://127.0.0.1:8030"
        const val EMULATOR_HOST_URL = "http://10.0.2.2:8030"
        private var _id = 0

        fun defaultUrls(): List<String> = listOf(LOCAL_URL, EMULATOR_HOST_URL)
    }

    /** Try the user-configured endpoint first, then safe device/emulator-local defaults. */
    suspend fun discover(): String? = withContext(Dispatchers.IO) {
        val candidates = (listOf(baseUrl.takeIf { it.isNotBlank() }) + defaultUrls())
            .filterNotNull().distinct()
        for (base in candidates) {
            try {
                // Probes the user-configured endpoint too, which may not be local.
                if (NetworkGuard.authorize("$base/health", userInitiated = true).blocked) continue
                val conn = URL("$base/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 1200
                conn.readTimeout = 1200
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) {
                    baseUrl = base
                    Log.i(TAG, "knowledge server found at $base")
                    return@withContext base
                }
            } catch (_: Exception) {
            }
        }
        null
    }

    private fun rpc(method: String, params: Map<String, Any?> = emptyMap(), base: String? = null): Map<String, Any?>? {
        val url = (base ?: baseUrl).trimEnd('/')
        val payload = mapOf(
            "jsonrpc" to "2.0",
            "id" to ++_id,
            "method" to method,
            "params" to params
        )
        val body = jsonEncode(payload)
        // The endpoint is user-configured in Settings, so a LAN host is normal and a remote one is
        // possible. Either way it asks the guard: local is allowed, remote respects the switch.
        val clearance = NetworkGuard.authorize(url, userInitiated = true)
        if (clearance.blocked) {
            Log.i(TAG, "knowledge RPC blocked: ${clearance.reason}")
            return null
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) return null
            val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            jsonDecode(text)
        } catch (e: Exception) {
            Log.w(TAG, "rpc $method failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────────── public knowledge API ─────────────────────

    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        discover() != null
    }

    /** Search the ToE corpus. Returns compact text: "file:line text" hits. */
    suspend fun search(query: String, limit: Int = 8): String? = withContext(Dispatchers.IO) {
        val resp = rpc("tools/call", mapOf(
            "name" to "toe.search",
            "arguments" to mapOf("query" to query, "limit" to limit)
        ))
        extractToolText(resp)?.let { json ->
            val sb = StringBuilder()
            try {
                val d = org.json.JSONObject(json)
                val results = d.optJSONArray("results") ?: return@let null
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    sb.append("[${r.optString("file")}] ")
                    val lines = r.optJSONArray("top_lines") ?: continue
                    for (j in 0 until minOf(3, lines.length())) {
                        val l = lines.optJSONObject(j) ?: continue
                        sb.append("L${l.optInt("line")}: ${l.optString("text").take(120)} | ")
                    }
                    sb.append("\n")
                }
            } catch (e: Exception) {
                null
            }
            sb.toString().ifBlank { null }
        }
    }

    /** Read a knowledge file (e.g. Vol08_BlackHoleThermodynamics.lean). */
    suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val resp = rpc("tools/call", mapOf(
            "name" to "toe.read",
            "arguments" to mapOf("name" to name)
        ))
        extractToolText(resp)?.let { json ->
            try {
                val d = org.json.JSONObject(json)
                d.optString("content").ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The axioms — his deepest layer. */
    suspend fun axioms(): String? = withContext(Dispatchers.IO) {
        val resp = rpc("tools/call", mapOf("name" to "toe.axioms", "arguments" to emptyMap<String, Any>()))
        extractToolText(resp)
    }

    /** Corpus stats. */
    suspend fun stats(): String? = withContext(Dispatchers.IO) {
        val resp = rpc("tools/call", mapOf("name" to "toe.stats", "arguments" to emptyMap<String, Any>()))
        extractToolText(resp)
    }

    /** His super-reasoning system prompt, served by the knowledge server. */
    suspend fun systemPrompt(): String? = withContext(Dispatchers.IO) {
        val resp = rpc("tools/call", mapOf("name" to "toe.system_prompt", "arguments" to emptyMap<String, Any>()))
        extractToolText(resp)?.let { json ->
            try {
                val d = org.json.JSONObject(json)
                d.optString("prompt").ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** git pull the latest ToE knowledge from GitHub. */
    suspend fun githubSync(): String? = withContext(Dispatchers.IO) {
        val resp = rpc("tools/call", mapOf("name" to "toe.github_sync", "arguments" to emptyMap<String, Any>()))
        extractToolText(resp)
    }

    // ───────────────────── tiny JSON plumbing ─────────────────────
    // (org.json is on Android; payload encode is trivial enough to hand-roll)

    private fun jsonEncode(m: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":").append(encodeValue(v))
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Int, is Long, is Boolean -> v.toString()
        is Map<*, *> -> {
            val sb = StringBuilder("{")
            var first = true
            @Suppress("UNCHECKED_CAST")
            for ((k, vv) in (v as Map<Any?, Any?>)) {
                if (!first) sb.append(",")
                first = false
                sb.append("\"").append(k.toString()).append("\":").append(encodeValue(vv))
            }
            sb.append("}").toString()
        }
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun jsonDecode(text: String): Map<String, Any?>? {
        return try {
            val d = org.json.JSONObject(text)
            val out = mutableMapOf<String, Any?>()
            for (k in d.keys()) out[k] = d.opt(k)
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun extractToolText(resp: Map<String, Any?>?): String? {
        val result = resp?.get("result") as? org.json.JSONObject ?: return null
        val content = result.optJSONArray("content") ?: return null
        if (content.length() == 0) return null
        return content.optJSONObject(0)?.optString("text")
    }
}
