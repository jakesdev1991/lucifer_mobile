package com.lucifer.mobile.omni_bridge.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * NemotronClient — Lucifer's native mind.
 *
 * Pure-Kotlin OpenAI-compatible client for integrate.api.nvidia.com.
 * No Kai sandbox, no Python bridge, no run-as: a direct HTTPS chat call with
 * 8-key rotation, persistent key config, and a model fallback chain. If every
 * route is dead he says so honestly instead of pretending.
 */
class NemotronClient(private val context: Context) {

    companion object {
        private const val TAG = "NemotronClient"
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"

        /** Fallback chain: his proven phone models first. */
        private val MODEL_CHAIN = listOf(
            "nvidia/nemotron-3-ultra",
            "nvidia/llama-3.1-nemotron-70b-instruct"
        )

        private const val KEY_FILE = "config/nvidia_key.txt"
        private const val MODEL_FILE = "config/llm_model.txt"

        /** His staged key pool (phone_secrets/nvidia_keys.txt — headless device). */
        private val EMBEDDED_KEYS = listOf(
            "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",
            "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",
            "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",
            "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",
            "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",
            "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",
            "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",
            "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"
        )
    }

    private var keyIndex = 0

    /** The system prompt = his soul. files/soul.md overrides the APK-asset default. */
    fun systemPrompt(): String {
        return try {
            val override = File(context.filesDir, "soul.md")
            if (override.exists() && override.length() > 0L) {
                override.readText()
            } else {
                context.assets.open("soul.md").bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "soul.md read failed, minimal identity", e)
            "You are Lucifer, an autonomous agent on Jake's Pixel 8a. Direct, technical, useful."
        }
    }

    /**
     * A single chat completion. Returns content, or an honest "[LLM] ..." error line.
     */
    suspend fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7
    ): String {
        val keys = keyPool()
        if (keys.isEmpty()) {
            return "[LLM] No NVIDIA API keys configured."
        }

        val models = modelChain()
        var lastErr = "unknown"
        for (m in models) {
            for (k in 0 until keys.size) {
                val key = keys[(keyIndex + k) % keys.size]
                try {
                    val content = call(key, m, messages, maxTokens, temperature)
                    keyIndex = (keyIndex + k) % keys.size
                    persistPreferred(key, m)
                    return content
                } catch (e: LlmAuthException) {
                    Log.w(TAG, "key ${key.take(12)} rejected for $m: ${e.message}")
                    lastErr = e.message ?: "auth"
                    // rotate to next key on auth failures
                } catch (e: LlmGoneException) {
                    Log.w(TAG, "model $m unavailable: ${e.message}")
                    lastErr = "$m: ${e.message}"
                    break // next model, keys stay
                } catch (e: Exception) {
                    Log.w(TAG, "call failed: ${e.message}")
                    lastErr = e.message ?: "io"
                }
            }
        }
        return "[LLM] All routes failed - $lastErr"
    }

    /** Convenience: system + single user turn. */
    suspend fun ask(system: String, user: String, maxTokens: Int = 1024): String {
        return chat(listOf("system" to system, "user" to user), maxTokens = maxTokens)
    }

    /** Convenience: soul-grounded single-turn ask. */
    suspend fun askWithSoul(user: String, maxTokens: Int = 1024): String {
        return chat(listOf("system" to systemPrompt(), "user" to user), maxTokens = maxTokens)
    }

    /**
     * Raw HTTPS call. HttpURLConnection — zero deps beyond org.json (Android core).
     * Blocking I/O; callers run on Dispatchers.IO.
     */
    private fun call(
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                put("messages", JSONArray().apply {
                    for ((role, content) in messages) {
                        put(JSONObject().apply {
                            put("role", role)
                            put("content", content)
                        })
                    }
                })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            when (conn.responseCode) {
                200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    return parseContent(text)
                }
                401, 403 -> throw LlmAuthException("HTTP ${conn.responseCode}")
                404 -> throw LlmGoneException("HTTP 404 model removed")
                429 -> throw LlmAuthException("HTTP 429 rate-limited, rotating key")
                else -> {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
                    } catch (_: Exception) { "" }
                    throw RuntimeException("HTTP ${conn.responseCode} $err")
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseContent(json: String): String {
        val root = JSONObject(json)
        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            return msg?.optString("content", "") ?: ""
        }
        return ""
    }

    /** Keys: Settings override file first, then the APK-embedded pool. */
    private fun keyPool(): List<String> {
        val pool = mutableListOf<String>()
        try {
            val f = File(context.filesDir, KEY_FILE)
            if (f.exists() && f.readText().trim().isNotEmpty()) pool.add(f.readText().trim())
        } catch (_: Exception) {}
        pool.addAll(EMBEDDED_KEYS)
        return pool.distinct()
    }

    private fun modelChain(): List<String> {
        return try {
            val f = File(context.filesDir, MODEL_FILE)
            if (f.exists()) {
                val override = f.readText().trim()
                if (override.isNotBlank() && override != "auto") return listOf(override)
            }
            MODEL_CHAIN
        } catch (_: Exception) {
            MODEL_CHAIN
        }
    }

    private fun persistPreferred(key: String, model: String) {
        try {
            val dir = File(context.filesDir, "config")
            dir.mkdirs()
            File(dir, "nvidia_key.txt").writeText(key)
            File(dir, "llm_model.txt").writeText(model)
        } catch (_: Exception) {}
    }

    /** Settings → NVIDIA API KEY save: writes the runtime override key. */
    fun saveRuntimeKey(key: String) {
        try {
            val dir = File(context.filesDir, "config")
            dir.mkdirs()
            File(dir, "nvidia_key.txt").writeText(key.trim())
        } catch (_: Exception) {}
    }

    private class LlmAuthException(msg: String) : Exception(msg)
    private class LlmGoneException(msg: String) : Exception(msg)

    /** Health probe — used by the launcher strip and Settings diagnostics. */
    suspend fun healthCheck(): Boolean {
        val reply = chat(
            listOf("user" to "Reply with the single word: OK"),
            maxTokens = 4,
            temperature = 0.0
        )
        return !reply.startsWith("[LLM]")
    }
}
