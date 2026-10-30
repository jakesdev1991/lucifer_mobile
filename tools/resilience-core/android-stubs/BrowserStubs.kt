// Type-check-only stand-ins. See AndroidStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.data.browser

data class BrowserResult(
    val url: String = "",
    val title: String = "",
    val html: String = "",
    val text: String = "",
    val success: Boolean = false,
    val error: String = ""
)

class LuciferBrowser(private val context: android.content.Context? = null) {
    suspend fun navigate(url: String, timeoutMs: Int = 20_000, userInitiated: Boolean = true): BrowserResult = BrowserResult()
}
