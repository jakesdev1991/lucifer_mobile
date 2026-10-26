package com.lucifer.mobile.omni_bridge.llm

import android.content.Context
import android.util.Log
import com.lucifer.mobile.security.SecretStore
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
 * encrypted user-supplied credentials and a model fallback chain. If every
 * route is dead he says so honestly instead of pretending.
 */
class NemotronClient(private val context: Context) {

    companion object {
        private const val TAG = "NemotronClient"
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        private const val VISION_MODEL = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

        /** Fallback chain: his proven phone models first. */
        private val MODEL_CHAIN = listOf(
            "nvidia/nemotron-3-ultra",
            "nvidia/llama-3.1-nemotron-70b-instruct"
        )

        private const val KEY_FILE = "config/nvidia_key.txt"
        private const val MODEL_FILE = "config/llm_model.txt"

        // Secrets must be supplied by the user and are never shipped in the APK.
    }

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
        temperature: Double = 0.7,
        requestTimeoutMs: Int = 120_000
    ): String {
        val keys = keyPool()
        if (keys.isEmpty()) {
            return "[LLM] No NVIDIA API keys configured."
        }

        val models = modelChain()
        var lastErr = "unknown"
        for (m in models) {
            for (key in keys) {
                try {
                    val content = call(key, m, messages, maxTokens, temperature, requestTimeoutMs)
                    persistPreferred(m)
                    return content
                } catch (e: LlmAuthException) {
                    Log.w(TAG, "configured key rejected for $m: ${e.message}")
                    lastErr = e.message ?: "auth"
                } catch (e: LlmGoneException) {
                    Log.w(TAG, "model $m unavailable: ${e.message}")
                    lastErr = "$m: ${e.message}"
                    break // next model
                } catch (e: Exception) {
                    Log.w(TAG, "call failed: ${e.message}")
                    lastErr = e.message ?: "io"
                }
            }
        }
        return "[LLM] All routes failed - $lastErr"
    }

    /** One image-grounded completion using NVIDIA's multimodal Nemotron Omni endpoint.
     * Callers must run this blocking-network implementation on Dispatchers.IO. */
    suspend fun chatWithImage(
        messages: List<Pair<String, String>>,
        imageJpeg: ByteArray,
        maxTokens: Int = 3_000,
        temperature: Double = 0.45,
        requestTimeoutMs: Int = 90_000
    ): String {
        if (imageJpeg.isEmpty() || imageJpeg.size > MAX_IMAGE_BYTES) {
            return "[LLM] Image must be non-empty and no larger than 2 MB."
        }
        val keys = keyPool()
        if (keys.isEmpty()) return "[LLM] No NVIDIA API keys configured."
        var lastErr = "unknown"
        for (key in keys) {
            try {
                return call(key, VISION_MODEL, messages, maxTokens, temperature, requestTimeoutMs, imageJpeg)
            } catch (e: LlmAuthException) {
                lastErr = e.message ?: "auth"
            } catch (e: Exception) {
                lastErr = e.message ?: "vision model unavailable"
            }
        }
        return "[LLM] Vision route failed - $lastErr"
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
        temperature: Double,
        requestTimeoutMs: Int,
        imageJpeg: ByteArray? = null
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = requestTimeoutMs.coerceIn(1_000, 120_000).coerceAtMost(15_000)
                readTimeout = requestTimeoutMs.coerceIn(1_000, 120_000)
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                val imageMessageIndex = if (imageJpeg != null) messages.indexOfLast { it.first == "user" } else -1
                val imageDataUrl = imageJpeg?.let {
                    "data:image/jpeg;base64,${android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)}"
                }
                put("messages", JSONArray().apply {
                    messages.forEachIndexed { index, (role, content) ->
                        put(JSONObject().apply {
                            put("role", role)
                            if (index == imageMessageIndex && imageDataUrl != null) {
                                put("content", JSONArray().apply {
                                    put(JSONObject().put("type", "text").put("text", content))
                                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))
                                })
                            } else {
                                put("content", content)
                            }
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

    /** The only credential source is the user's encrypted Settings value. */
    private fun keyPool(): List<String> =
        SecretStore.read(context, KEY_FILE)?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()

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

    private fun persistPreferred(model: String) {
        // Persist only non-secret model preference; credential is already stored encrypted.
        try {
            val dir = File(context.filesDir, "config")
            dir.mkdirs()
            File(dir, "llm_model.txt").writeText(model)
        } catch (_: Exception) {}
    }

    /** Settings → NVIDIA API KEY save: encrypt the user's runtime credential. */
    fun saveRuntimeKey(key: String): Boolean {
        val normalized = key.trim()
        if (normalized.isEmpty()) {
            SecretStore.delete(context, KEY_FILE)
            return true
        }
        if (normalized.length !in 16..512 || normalized.any { it.isWhitespace() }) return false
        return SecretStore.write(context, KEY_FILE, normalized)
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
