package com.lucifer.mobile.omni_bridge.kai_bridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

// Browser Bridge - simplified interface for browser operations
class KaiBrowserBridge(
    private val kaiBridge: KaiBridge
) {

    suspend fun initialize() {
        kaiBridge.connect()
    }

    suspend fun navigate(url: String): BrowserResult {
        val result = kaiBridge.navigate(url)
        return result?.let {
            BrowserResult(
                url = url,
                text = it.data,
                success = it.success,
                error = it.error ?: ""
            )
        } ?: BrowserResult(url = url, success = false, error = "Not connected")
    }

    suspend fun click(selector: String): ClickResult {
        val result = kaiBridge.click(selector)
        return result?.let {
            ClickResult(success = it.success, error = it.error ?: "", newUrl = it.data)
        } ?: ClickResult(success = false, error = "Not connected")
    }

    suspend fun typeText(selector: String, text: String): TypeResult {
        val result = kaiBridge.evaluate("document.querySelector('$selector').value = '$text'; true")
        return result?.let {
            TypeResult(success = it.success, error = it.error ?: "")
        } ?: TypeResult(success = false, error = "Not connected")
    }

    suspend fun getContent(): BrowserResult {
        val result = kaiBridge.evaluate("document.body.innerText")
        return result?.let {
            BrowserResult(
                url = "",
                text = it.data,
                success = it.success,
                error = it.error ?: ""
            )
        } ?: BrowserResult(success = false, error = "Not connected")
    }

    suspend fun evaluate(script: String): EvaluateResult {
        val result = kaiBridge.evaluate(script)
        return result?.let {
            EvaluateResult(success = it.success, result = it.data, error = it.error ?: "")
        } ?: EvaluateResult(success = false, error = "Not connected")
    }

    suspend fun screenshot(fullPage: Boolean = false): String? {
        val result = kaiBridge.screenshot(fullPage)
        return result?.data
    }

    suspend fun getCookies(): Map<String, String> {
        val result = kaiBridge.evaluate("document.cookie")
        val cookieStr = result?.data ?: ""
        return cookieStr.split("; ").associate {
            val parts = it.split("=")
            parts[0] to parts.getOrElse(1) { "" }
        }
    }

    suspend fun setCookies(cookies: Map<String, String>): Boolean {
        // In production: set cookies via browser automation
        return true
    }

    fun close() {
        kaiBridge.disconnect()
    }

    fun shutdown() {
        close()
    }
}

// Data classes for browser results (matching KaiResponse.BrowserResult fields)
data class BrowserResult(
    val url: String = "",
    val text: String = "",
    val success: Boolean = false,
    val error: String = ""
)

data class ClickResult(
    val success: Boolean = false,
    val error: String = "",
    val newUrl: String = ""
)

data class TypeResult(
    val success: Boolean = false,
    val error: String = ""
)

data class EvaluateResult(
    val success: Boolean = false,
    val result: Any? = null,
    val error: String = ""
)