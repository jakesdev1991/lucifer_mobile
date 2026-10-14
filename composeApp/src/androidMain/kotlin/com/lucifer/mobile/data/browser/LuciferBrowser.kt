package com.lucifer.mobile.data.browser

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * LuciferBrowser — native fetch-and-extract. No Kai sandbox, no Playwright.
 *
 * Headless page retrieval with sensible desktop UA, redirects followed, gzip
 * handled, and a lightweight HTML→text extractor (script/style stripped,
 * tags removed, entities decoded, whitespace squeezed). Limit-clamped so a
 * hostile page can't blow the heap. click/type/evaluate are honest "not
 * supported without a JS engine" results — this is a reader, not a driver.
 */
class LuciferBrowser(private val context: android.content.Context? = null) {

    companion object {
        private const val TAG = "LuciferBrowser"
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val MAX_BYTES = 2_000_000       // 2MB clamp
        private const val DEFAULT_TIMEOUT = 20_000
        private val ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "#39" to "'", "mdash" to "—", "ndash" to "–", "hellip" to "…"
        )
    }

    private val cookieJar = mutableMapOf<String, String>()

    fun initialize() { /* connection-per-request; nothing to warm */ }

    /** GET a URL and extract readable text. Redirects followed up to 5 hops. */
    suspend fun navigate(url: String, timeoutMs: Int = DEFAULT_TIMEOUT): BrowserResult {
        var current = url
        var hops = 0
        var last: HttpURLConnection? = null
        return try {
            while (hops++ < 5) {
                last = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    setRequestProperty("Accept-Encoding", "gzip")
                    if (cookieJar.isNotEmpty()) {
                        setRequestProperty("Cookie", cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }
                    instanceFollowRedirects = true
                }
                when (last.responseCode) {
                    in 200..299 -> {
                        captureCookies(last)
                        val bytes = readClamped(last, MAX_BYTES)
                        val html = decodeBody(last, bytes)
                        val text = extractText(html)
                        return BrowserResult(
                            url = current,
                            title = extractTitle(html),
                            html = if (html.length > 200_000) "" else html,
                            text = if (text.length > 100_000) text.take(100_000) else text,
                            links = extractLinks(html, current),
                            cookies = cookieJar.toMap(),
                            success = true
                        )
                    }
                    in 300..399 -> {
                        val loc = last.getHeaderField("Location")
                        if (loc.isNullOrBlank()) break
                        current = java.net.URI(current).resolve(loc).toString()
                        continue
                    }
                    else -> {
                        return BrowserResult(
                            url = current,
                            success = false,
                            error = "HTTP ${last.responseCode}"
                        )
                    }
                }
            }
            BrowserResult(url = current, success = false, error = "too many redirects")
        } catch (e: Exception) {
            Log.w(TAG, "navigate($url) failed: ${e.message}")
            BrowserResult(url = url, success = false, error = e.message ?: "network error")
        } finally {
            last?.disconnect()
        }
    }

    /** JS-driver ops need a real JS engine — say so honestly. */
    suspend fun click(selector: String): ClickResult =
        ClickResult(success = false, error = "click requires a JS browser engine (native fetcher is read-only)")

    suspend fun typeText(selector: String, text: String): TypeResult =
        TypeResult(success = false, error = "typing requires a JS browser engine (native fetcher is read-only)")

    suspend fun evaluate(script: String): EvaluateResult =
        EvaluateResult(success = false, error = "JS evaluation requires a JS browser engine (native fetcher is read-only)")

    suspend fun getContent(): BrowserResult =
        BrowserResult(success = false, error = "no active session — call navigate()")

    suspend fun screenshot(fullPage: Boolean = false): String? = null

    fun getCookies(): Map<String, String> = cookieJar.toMap()

    fun setCookies(cookies: Map<String, String>) {
        cookieJar.putAll(cookies)
    }

    fun close() { cookieJar.clear() }
    fun shutdown() { close() }

    // ───────────── internals ─────────────

    private fun readClamped(conn: HttpURLConnection, max: Int): ByteArray {
        val stream = if (conn.contentEncoding == "gzip") {
            java.util.zip.GZIPInputStream(conn.inputStream)
        } else conn.inputStream
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        stream.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (out.size() + n > max) { out.write(buf, 0, max - out.size()); break }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun decodeBody(conn: HttpURLConnection, bytes: ByteArray): String {
        val charset = conn.contentType?.let { ct ->
            Regex("charset=([A-Za-z0-9_\\-]+)").find(ct)?.groupValues?.get(1)
        } ?: "UTF-8"
        return try {
            String(bytes, java.nio.charset.Charset.forName(charset))
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun captureCookies(conn: HttpURLConnection) {
        // headerField loop: index-based access to also catch multiple Set-Cookie
        var i = 1
        while (true) {
            val key = conn.getHeaderFieldKey(i) ?: break
            if (key.equals("Set-Cookie", true)) {
                val v = conn.getHeaderField(i) ?: break
                val pair = v.substringBefore(";").split("=", limit = 2)
                if (pair.size == 2) cookieJar[pair[0].trim()] = pair[1].trim()
            }
            i++
        }
    }

    private fun extractTitle(html: String): String {
        val m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            .matcher(html)
        return if (m.find()) decodeEntities(stripTags(m.group(1))).trim().take(200) else ""
    }

    /** Strip script/style blocks, then tags, then squeeze whitespace. */
    private fun extractText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<script.*?</script>"), "")
            .replace(Regex("(?is)<style.*?</style>"), "")
            .replace(Regex("(?is)<!--.*?-->"), "")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6])[^>]*>"), "\n")
        val text = stripTags(cleaned)
        return decodeEntities(text)
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun stripTags(s: String): String =
        Regex("<[^>]+>").replace(s, " ")

    private fun decodeEntities(s: String): String {
        var out = s
        for ((k, v) in ENTITIES) {
            out = out.replace("&$k;", v).replace("&$k", v)
        }
        return out
    }

    private fun extractLinks(html: String, baseUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        val m = Pattern.compile("href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html)
        try {
            val base = java.net.URI(baseUrl)
            while (m.find() && out.size < 50) {
                val href = m.group(1)
                if (href.startsWith("javascript:") || href.startsWith("#")) continue
                try { out.add(base.resolve(href).toString()) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return out.toList()
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
