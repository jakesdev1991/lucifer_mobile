package com.lucifer.mobile.data.browser

import android.content.Context
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBrowserBridge
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuciferBrowser {

    private val kaiBridge: KaiBrowserBridge
    private val scope = CoroutineScope(Dispatchers.IO)

    constructor(context: Context) {
        val bridge = KaiBridge(context)
        this.kaiBridge = KaiBrowserBridge(bridge)
    }

    fun initialize() {
        scope.launch {
            kaiBridge.initialize()
        }
    }

    suspend fun navigate(url: String): BrowserResult {
        val result = kaiBridge.navigate(url)
        return BrowserResult(
            url = result.url,
            success = result.success,
            text = result.text,
            error = result.error
        )
    }

    suspend fun click(selector: String): ClickResult {
        val result = kaiBridge.click(selector)
        return ClickResult(
            success = result.success,
            error = result.error,
            newUrl = result.newUrl
        )
    }

    suspend fun typeText(selector: String, text: String): TypeResult {
        val result = kaiBridge.typeText(selector, text)
        return TypeResult(
            success = result.success,
            error = result.error
        )
    }

    suspend fun getContent(): BrowserResult {
        val result = kaiBridge.getContent()
        return BrowserResult(
            url = result.url,
            text = result.text,
            success = result.success,
            error = result.error
        )
    }

    suspend fun evaluate(script: String): EvaluateResult {
        val result = kaiBridge.evaluate(script)
        return EvaluateResult(
            success = result.success,
            result = result.result,
            error = result.error
        )
    }

    suspend fun screenshot(fullPage: Boolean = false): String? {
        return kaiBridge.screenshot(fullPage)
    }

    suspend fun getCookies(): Map<String, String> {
        return kaiBridge.getCookies()
    }

    suspend fun setCookies(cookies: Map<String, String>): Boolean {
        return kaiBridge.setCookies(cookies)
    }

    fun close() {
        kaiBridge.close()
    }

    fun shutdown() {
        close()
    }
}

data class BrowserResult(
    val url: String = "",
    val title: String = "",
    val html: String = "",
    val text: String = "",
    val links: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val cookies: Map<String, String> = emptyMap(),
    val screenshotBase64: String = "",
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