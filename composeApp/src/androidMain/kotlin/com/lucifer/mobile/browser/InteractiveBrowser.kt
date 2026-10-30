package com.lucifer.mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream

/**
 * A real browser: JavaScript, DOM, cookies, sign-in, and the ability to click.
 *
 * This is the honest counterpart to `data/browser/LuciferBrowser`, which is a fetcher with a text
 * extractor. That one is in the egress ledger and can ride the Tor tunnel; this one cannot be, because
 * a `WebView` owns its sockets and never asks `EgressHttp` for permission. That single fact drives
 * every design decision here:
 *
 *  - the panel refuses to open it when Tor is selected without a system VPN ([BrowserPolicy.availability]);
 *  - every action that can hand something to a server is confirmed first ([BrowserPolicy.decide]);
 *  - cookies are the user's, not the app's: they live in WebView's own jar, are never exported, and are
 *    cleared on request from the same panel that shows them.
 *
 * What it deliberately is not: a headless scraper (no page is fetched behind your back), a session that
 * runs in the background (the WebView is created and destroyed with the panel), or a way to pre-fill
 * credentials (typing is an action you confirm, with no password store behind it).
 */
class InteractiveBrowser(private val context: Context) {

    private companion object {
        const val TAG = "InteractiveBrowser"
        const val MAX_SCREENSHOT_BYTES = 900_000
        const val SCREENSHOT_QUALITY = 80

        /**
         * A desktop-ish UA. Mobile sites are often a stripped "open the app" page, and this browser
         * has no app to open. WebView's own default would identify the WebView, which some sites
         * block outright.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8a) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
    }

    @Volatile private var webView: WebView? = null

    /** Reported back to the panel so it can draw a truthful status line. */
    data class Snapshot(
        val url: String = "",
        val title: String = "",
        val progress: Int = 0,
        val canGoBack: Boolean = false
    )

    /** Create (or reuse) the WebView. Must run on the main thread: WebView is not thread-safe. */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION") // the two file-URL settings are deprecated and still the only switch
    fun attach(): WebView {
        webView?.let { return it }
        val view = WebView(context.applicationContext)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // No file or content access: a page must not be able to read this device's storage.
            allowFileAccess = false
            allowContentAccess = false
            // A page reached over http must not be able to read file:// or content:// URIs, and a page
            // loaded from a file must not reach out to anything. Both default to false on modern
            // WebView; both are set explicitly because "the default is fine" is how this breaks later.
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            // Cookies are first-party only. Third-party cookies are how a page you did not open
            // recognises you across sites, and nothing here needs that.
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = USER_AGENT
            javaScriptCanOpenWindowsAutomatically = false
            // No popups, no new windows: everything lands in this one WebView so it stays reviewable.
            setSupportMultipleWindows(false)
        }
        // The app's own cookie policy is the only one that applies; no cross-app session sharing.
        CookieManager.getInstance().setAcceptCookie(true)
        webView = view
        Log.i(TAG, "webview created · js on · third-party cookies off · file access off")
        return view
    }

    /** Install the navigation callback. Separate from [attach] so the caller owns the state. */
    fun installClient(onState: (Snapshot) -> Unit, onBlocked: (String) -> Unit) {
        val view = attach()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onState(Snapshot(url = url.orEmpty(), progress = 0, canGoBack = view?.canGoBack() == true))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                onState(
                    Snapshot(
                        url = url.orEmpty(),
                        title = view?.title.orEmpty(),
                        progress = 100,
                        canGoBack = view?.canGoBack() == true
                    )
                )
            }

            /**
             * Every navigation is re-checked, including ones a page triggers itself. A redirect to
             * `intent://`, `tel:` or a custom scheme would otherwise leave the app silently.
             */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                if (scheme != "http" && scheme != "https") {
                    onBlocked("Blocked a non-web link ($scheme). Lucifer does not hand you to another app.")
                    return true
                }
                return false
            }
        }
    }

    /** Load a URL. Returns false when the policy refuses it. */
    fun navigate(rawUrl: String, available: Boolean = true): Boolean {
        val decision = BrowserPolicy.decide(BrowserAction.NAVIGATE, rawUrl, available)
        if (decision.refused) return false
        val url = normalize(rawUrl)
        attach().loadUrl(url)
        return true
    }

    fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    fun reload() {
        webView?.reload()
    }

    /**
     * Click the first element matching [selector].
     *
     * The confirmation is the caller's job ([BrowserPolicy.decide] said "ask first"); this function
     * assumes a person has just agreed and performs exactly what it was told.
     */
    suspend fun click(selector: String): String = evaluate(BrowserPolicy.clickScript(selector))

    /** Type into a field and fire the events frameworks listen for. */
    suspend fun type(selector: String, text: String): String =
        evaluate(BrowserPolicy.typeScript(selector, text))

    /** Run a script. Callers must have confirmed it; [BrowserPolicy] refuses anything oversized. */
    suspend fun evaluate(script: String): String = evaluateOnMain(script)

    /** The page's visible text, bounded. Useful for handing to the model. */
    suspend fun pageText(limit: Int = 20_000): String {
        val raw = evaluate("(function(){var t=document.body?document.body.innerText:'';return t;})()")
        // evaluateJavascript returns a JSON-encoded string literal; decode it before handing it on.
        return decodeJsonString(raw).take(limit)
    }

    /**
     * Capture the rendered page. Returns null when nothing is drawn yet.
     *
     * Compression is deliberate: a full-page PNG of a modern site is megabytes, and every byte here
     * would be shipped to the model. The ceiling is checked after encoding and reported, not hidden.
     */
    suspend fun screenshot(): ByteArray? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val view = webView
                if (view == null || view.width <= 0 || view.height <= 0) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                } else {
                    val bytes = try {
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        val encoded = ByteArrayOutputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, stream)
                            stream.toByteArray()
                        }
                        bitmap.recycle()
                        encoded
                    } catch (error: Exception) {
                        Log.w(TAG, "screenshot failed: ${error.javaClass.simpleName}")
                        null
                    }
                    val accepted = bytes?.takeIf { it.size <= MAX_SCREENSHOT_BYTES }
                    if (continuation.isActive) continuation.resumeWith(Result.success(accepted))
                }
            }
        }

    /** The cookies WebView is actually holding, for the panel to show and clear. Never exported. */
    fun cookieSummary(): String {
        val cookies = CookieManager.getInstance().getCookie(webView?.url.orEmpty()).orEmpty()
        if (cookies.isBlank()) return "no cookies for this site"
        val names = cookies.split(';').mapNotNull { it.substringBefore('=').trim().ifBlank { null } }
        return "${names.size} cookie(s): ${names.take(8).joinToString(", ")}"
    }

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.clearFormData()
        Log.i(TAG, "cookies, cache and form data cleared")
    }

    fun currentUrl(): String = webView?.url.orEmpty()

    /** Destroy the WebView. The panel calls this on dispose: nothing survives the screen. */
    fun destroy() {
        val view = webView ?: return
        webView = null
        try {
            CookieManager.getInstance().flush()
            view.stopLoading()
            view.loadUrl("about:blank")
            view.removeAllViews()
            view.destroy()
            Log.i(TAG, "webview destroyed")
        } catch (error: Exception) {
            Log.w(TAG, "webview destroy failed: ${error.javaClass.simpleName}")
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return "https://$trimmed"
    }

    /**
     * `evaluateJavascript` answers on the main thread's message queue. WebView calls are main-thread
     * only, so this suspends the caller and hops, rather than pretending it can be called from IO.
     */
    private suspend fun evaluateOnMain(script: String): String =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                val view = webView
                if (view == null) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(""))
                } else {
                    view.evaluateJavascript(script) { result ->
                        if (continuation.isActive) continuation.resumeWith(Result.success(result ?: ""))
                    }
                }
            }
        }

    private fun decodeJsonString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return trimmed
        return try {
            org.json.JSONTokener(trimmed).nextValue() as? String ?: ""
        } catch (_: Exception) {
            trimmed
        }
    }

    /** Only http and https are ever loaded; everything else is refused by policy first. */
    internal fun isWebUrl(url: String): Boolean =
        Uri.parse(url).scheme?.lowercase()?.let { it == "http" || it == "https" } == true
}
