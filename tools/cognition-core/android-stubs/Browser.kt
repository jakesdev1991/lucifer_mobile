@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.data.browser

class BrowserResult(
    val success: Boolean = false,
    val url: String = "",
    val title: String = "",
    val text: String = "",
    val html: String = "",
    val error: String = ""
)

class LuciferBrowser(private val context: android.content.Context) {
    suspend fun navigate(url: String, timeoutMs: Int = 20_000, userInitiated: Boolean = true): BrowserResult =
        BrowserResult(success = false, url = url, error = "no network in the harness")
}
