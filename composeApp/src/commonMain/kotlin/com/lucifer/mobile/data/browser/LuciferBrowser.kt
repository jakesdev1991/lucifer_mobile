package com.lucifer.mobile.data.browser

import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBrowserBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuciferBrowser {

    private val kaiBridge = KaiBrowserBridge()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun initialize(context: android.content.Context) {
        scope.launch {
            kaiBridge.initialize()
        }
    }
    
    fun navigate(url: String): BrowserResult {
        return kaiBridge.navigate(url)
    }
    
    fun click(selector: String): ClickResult {
        return kaiBridge.click(selector)
    }
    
    fun typeText(selector: String, text: String): TypeResult {
        return kaiBridge.type_text(selector, text)
    }
    
    fun getContent(): BrowserResult {
        return kaiBridge.get_content()
    }
    
    fun evaluate(script: String): EvaluateResult {
        return kaiBridge.evaluate(script)
    }
    
    fun screenshot(fullPage: Boolean = false): String? {
        return kaiBridge.screenshot(fullPage)
    }
    
    fun getCookies(): Map<String, String> {
        return kaiBridge.get_cookies()
    }
    
    fun setCookies(cookies: Map<String, String>): Boolean {
        return kaiBridge.set_cookies(cookies)
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