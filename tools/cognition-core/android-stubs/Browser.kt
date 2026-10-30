// Type-check-only stand-in, with a fixture so the toolbox's own logic can be exercised.
//
// The toolbox is real code: it decides which argument is a URL, which is too short to be a research
// question, and what to say when a fetch fails. The *browser* is not what is under test, so it is
// programmable here and every call is recorded.
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

object BrowserFixture {
    @Volatile var result: BrowserResult = BrowserResult(success = false, error = "no network in the harness")
    val calls = mutableListOf<String>()

    fun reset() {
        result = BrowserResult(success = false, error = "no network in the harness")
        calls.clear()
    }
}

class LuciferBrowser(private val context: android.content.Context) {
    suspend fun navigate(url: String, timeoutMs: Int = 20_000, userInitiated: Boolean = true): BrowserResult {
        BrowserFixture.calls += url
        return BrowserFixture.result
    }
}
