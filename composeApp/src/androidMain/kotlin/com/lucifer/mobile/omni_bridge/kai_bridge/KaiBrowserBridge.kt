package com.lucifer.mobile.omni_bridge.kai_bridge

/**
 * Browser Bridge — honest status only.
 *
 * The Kai sandbox has no wired Playwright/browser automation in this build (the old code pointed at
 * `/tmp/omni_bridge/browser/` python scripts, which nothing deploys — see ADVERSARIAL_REVIEW.md). Rather than
 * pretend, every action reports the real state: if run-as is blocked, the error says exactly that.
 *
 * The app's own JS-capable browser is `browser/InteractiveBrowser` (WebView), reached from the
 * Browser panel's INTERACTIVE mode — not through this bridge.
 */
class KaiBrowserBridge(
    private val kaiBridge: KaiBridge
) {
    suspend fun initialize() {
        kaiBridge.connect()
    }

    private fun unavailable(): BrowserResult = BrowserResult(
        success = false,
        error = "Kai browser automation is not wired in this build; use the Browser panel's INTERACTIVE mode (WebView)"
    )

    suspend fun navigate(url: String): BrowserResult = unavailable()

    suspend fun click(selector: String): ClickResult = ClickResult(
        success = false,
        error = "Kai browser automation is not wired in this build; the WebView path in the Browser panel is the interactive one"
    )

    suspend fun typeText(selector: String, text: String): TypeResult = TypeResult(
        success = false,
        error = "Kai browser automation is not wired in this build; no text was typed anywhere"
    )

    suspend fun getContent(): BrowserResult = unavailable()

    suspend fun evaluate(script: String): EvaluateResult = EvaluateResult(
        success = false,
        error = "Kai browser automation is not wired in this build; no script was run"
    )

    suspend fun screenshot(fullPage: Boolean = false): String? = null

    /** Cookies belong to the WebView path, not to a cross-app sandbox; never exported. */
    suspend fun getCookies(): Map<String, String> = emptyMap()

    suspend fun setCookies(cookies: Map<String, String>): Boolean = false

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
