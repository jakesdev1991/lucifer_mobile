package com.lucifer.mobile.browser

/**
 * What an interactive browser is allowed to do, decided before any of it happens.
 *
 * The read-only fetcher had a simple rule: nothing mutates, so nothing needs asking. A real browser
 * changes that, because a click is not a read — it is a POST, a message, a purchase, a confirmation
 * link. The user's standing instruction is that anything which *submits something to the world* is
 * confirmed first, so this object classifies each action and says whether it needs a human.
 *
 * It also answers a question the transport work raised: **a `WebView` does not go through Lucifer's
 * Tor tunnel.** WebView owns its sockets, ignores `EgressHttp`, and never appears in the egress ledger.
 * Under TOR transport that would be a silent leak of exactly the kind `TransportPolicy` exists to
 * prevent, so the browser is refused there unless the *system* is behind a VPN (Orbot's VPN mode or
 * WireGuard), where WebView's traffic is captured at the IP layer.
 *
 * Pure Kotlin, no Android imports: `tools/browser-core/spec_check.py` executes the same rules.
 */

enum class BrowserAction(val label: String) {
    /** Load a URL. A GET with no side effects on this device. */
    NAVIGATE("navigate"),

    /** Extract text / read the page. No side effects. */
    READ("read"),

    /** Capture the rendered page. Local only. */
    SCREENSHOT("screenshot"),

    /** Dispatch a click at a selector. Whatever the page does next is the side effect. */
    CLICK("click"),

    /** Put text into a field. Harmless alone; the submission that follows is not. */
    TYPE("type"),

    /** Run a script in the page. Arbitrary by definition. */
    EVALUATE("evaluate"),

    /** Scroll the page. A read of layout; no data leaves the device. */
    SCROLL("scroll")
}

/** Why a decision came out the way it did, in words that can be shown to a person. */
data class BrowserDecision(
    val allowed: Boolean,
    val needsConfirmation: Boolean,
    val reason: String = ""
) {
    val refused: Boolean get() = !allowed
}

object BrowserPolicy {

    /** Longest URL the WebView will be asked to load. */
    const val MAX_URL_CHARS = 2_048

    /** Longest selector. Anything longer is not a selector. */
    const val MAX_SELECTOR_CHARS = 300

    /** Longest text typed into a field in one action. */
    const val MAX_TYPE_CHARS = 2_000

    /** Longest script. Large scripts are programs, and this is not a development environment. */
    const val MAX_SCRIPT_CHARS = 4_000

    /**
     * Is the interactive browser usable at all right now?
     *
     * @param transport the app's egress transport (DIRECT or TOR).
     * @param systemVpnPresent whether the OS reports an app-provided VPN on the active network.
     */
    fun availability(transport: String, systemVpnPresent: Boolean): BrowserDecision = when {
        !transport.equals("TOR", ignoreCase = true) ->
            BrowserDecision(true, false, "WebView opens its own sockets, outside the ledger.")
        systemVpnPresent ->
            BrowserDecision(
                true,
                false,
                "System VPN present · WebView traffic is caught at the IP layer, so Tor mode covers it."
            )
        else ->
            BrowserDecision(
                false,
                false,
                "Tor is selected and no system VPN is running. A WebView opens its own sockets: it would " +
                    "bypass Lucifer's tunnel and never appear in the ledger, which is exactly the leak Tor " +
                    "mode exists to prevent. Run Orbot's VPN mode (or WireGuard) to browse here, or switch " +
                    "the transport to DIRECT and accept that these page requests are direct."
            )
    }

    /**
     * Classify one action.
     *
     * Reads are free. Anything that can hand something to a remote server waits for a person — the
     * click *is* the submission, and asking after it has happened is theatre.
     */
    fun decide(action: BrowserAction, target: String, available: Boolean = true): BrowserDecision {
        if (!available) {
            return BrowserDecision(false, false, "The interactive browser is not available in this transport.")
        }
        val clean = target.trim()

        if (action == BrowserAction.NAVIGATE) {
            if (clean.length > MAX_URL_CHARS) {
                return BrowserDecision(false, false, "That URL is too long for the browser.")
            }
            if (clean.isBlank()) return BrowserDecision(false, false, "Enter a URL first.")
            val lower = clean.lowercase()
            if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("file:") ||
                lower.startsWith("blob:")
            ) {
                return BrowserDecision(false, false, "Only http and https pages can be opened.")
            }
        }

        if (action == BrowserAction.CLICK || action == BrowserAction.TYPE || action == BrowserAction.EVALUATE) {
            if (clean.isBlank()) {
                return BrowserDecision(false, false, "Nothing to act on.")
            }
            val limit = when (action) {
                BrowserAction.CLICK -> MAX_SELECTOR_CHARS
                BrowserAction.TYPE -> MAX_TYPE_CHARS
                else -> MAX_SCRIPT_CHARS
            }
            if (clean.length > limit) {
                return BrowserDecision(false, false, "That is longer than ${limit} characters.")
            }
        }

        return when (action) {
            BrowserAction.NAVIGATE, BrowserAction.READ, BrowserAction.SCREENSHOT, BrowserAction.SCROLL ->
                BrowserDecision(true, false)
            BrowserAction.CLICK -> BrowserDecision(
                true,
                true,
                "A click is not a read: it can post, message, buy or delete. Confirm it first."
            )
            BrowserAction.TYPE -> BrowserDecision(
                true,
                true,
                "Text typed into a page is data you are giving it. Confirm it first."
            )
            BrowserAction.EVALUATE -> BrowserDecision(
                true,
                true,
                "A script can do anything the page can do. Confirm it first."
            )
        }
    }

    /**
     * Selectors the click/type actions accept.
     *
     * Deliberately narrow: `#id`, `.class`, `tag`, `[attr=value]`, and descendant/child chains. It is
     * interpolated into a JavaScript string, so the character set is the safety boundary — no
     * backslash, no backtick, no `$`, no interpolation of any kind.
     *
     * Quotes and parentheses are the interesting cases. Quotes are *allowed* because CSS attribute
     * values legitimately need them (`a[href='/x']`) and [jsString] escapes them anyway — but a
     * stray quote is still the cheapest escape attempt there is, so it stays on the reject list
     * below. Parentheses are *not* allowed: `div)` is never a selector, and a selector that only
     * reaches the page as a `querySelector` SyntaxError is a failure the user reads as "nothing
     * happened".
     */
    fun isValidSelector(selector: String): Boolean {
        val s = selector.trim()
        if (s.isEmpty() || s.length > MAX_SELECTOR_CHARS) return false
        if (s.lowercase().startsWith("javascript:")) return false
        // A literal space rather than \s: a tab or a newline in a selector is never intentional here.
        if (!Regex("""^[A-Za-z0-9#._\-\[\]='"+*>:+~/ ]+$""").matches(s)) return false
        return !s.contains("\\") && !s.contains("`") && !s.contains("$") && !s.contains("\"")
    }

    /** JavaScript that clicks the first match, written once so the page sees one canonical action. */
    fun clickScript(selector: String): String {
        val escaped = jsString(selector.trim())
        return "(function(){var el=document.querySelector($escaped);" +
            "if(!el){return 'NO_MATCH';}el.click();return 'CLICKED';})()"
    }

    /** JavaScript that sets a field's value and fires the events a framework listens for. */
    fun typeScript(selector: String, text: String): String {
        val s = jsString(selector.trim())
        val t = jsString(text)
        return "(function(){var el=document.querySelector($s);if(!el){return 'NO_MATCH';}" +
            "el.focus();el.value=$t;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'TYPED';})()"
    }

    /** Escape a value for single-quoted JavaScript. No backslash, no quote, no newline survives. */
    fun jsString(raw: String): String {
        val builder = StringBuilder("'")
        for (character in raw) {
            when (character) {
                '\\' -> builder.append("\\\\")
                '\'' -> builder.append("\\'")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '<' -> builder.append("\\u003c")
                '>' -> builder.append("\\u003e")
                else -> builder.append(character)
            }
        }
        return builder.append("'").toString()
    }

    /** A stable label for the confirmation sheet: what is about to happen, in one line. */
    fun describe(action: BrowserAction, target: String): String = when (action) {
        BrowserAction.CLICK -> "Click `${target.trim().take(120)}`"
        BrowserAction.TYPE -> "Type ${target.length} characters into the focused field"
        BrowserAction.EVALUATE -> "Run a ${target.length}-character script in the page"
        BrowserAction.SCROLL -> "Scroll the page by ${target.trim().take(40)} pixels"
        else -> action.label
    }

    // ── SSRF / DNS-rebinding guard for WebView navigation ───────────
    //
    // A WebView resolves DNS itself, so the read-only fetcher's resolve-then-check cannot be reused
    // here directly. The defence that survives is: only public-looking hostnames are allowed, IP
    // literals in private/reserved ranges are refused, and every navigation (including redirects and
    // page-initiated ones) is re-checked in shouldOverrideUrlLoading/onPageStarted. DNS rebinding is
    // still possible in principle against a hostile authoritative server; that is why this mode is
    // refused under Tor without a system VPN and is never the default.

    /**
     * True when [host] is an IP literal in a private, loopback, link-local, CGNAT, or reserved range.
     * Mirrors the literal check in `data/browser/LuciferBrowser`.
     */
    fun isPrivateLiteral(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h.contains(':')) {
            if (h == "::" || h == "::1") return true
            val first = h.substringBefore(':', "")
            if (first.length >= 2) {
                val byte = first.take(2).toIntOrNull(16) ?: return true
                if ((byte and 0xfe) == 0xfc) return true // ULA fc00::/7
                if (first.startsWith("fe8") || first.startsWith("fe9") ||
                    first.startsWith("fea") || first.startsWith("feb")
                ) return true // link-local fe80::/10
            }
            return false
        }
        if (!isIpv4Literal(h)) return false
        val parts = h.split('.')
        if (parts.size != 4) return true
        val octets = parts.map { it.toIntOrNull() ?: return true }
        val a = octets[0]
        val b = octets[1]
        if (a == 10 || a == 127 || a == 0) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        if (a == 169 && b == 254) return true
        if (a == 100 && b in 64..127) return true // CGNAT 100.64.0.0/10
        return a >= 240
    }

    /** Strict IPv4 literal test (four decimal octets, no leading-zero tricks beyond single digits). */
    fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
    }

    /**
     * True when [host] is a bare or bracketed IPv6 literal.
     *
     * The brackets matter: `navigationRefusal` deliberately keeps them on a bracketed URL so the range
     * check sees the address, and this test used to reject `"[::1]"` for containing `[`. That made
     * `if (isIpv4Literal(host) || isIpv6Literal(host))` skip the private-range check entirely, so
     * `https://[::1]/`, `https://[fc00::1]/` and `https://[fe80::1]/` were navigable — a loopback or
     * link-local target the WebView would happily fetch. The Python mirror in `tools/browser-core`
     * tested `":" in host` instead, so it never saw the hole; the Kotlin harness did.
     */
    fun isIpv6Literal(host: String): Boolean {
        val h = host.trim('[', ']')
        return h.contains(':') && h.all { it.isDigit() || it.isLetter() || it == ':' || it == '.' }
    }

    /**
     * Decide whether a WebView navigation target is acceptable. Only http/https with a public-looking
     * host pass; private IP literals and local/internal names are refused before the WebView resolves
     * anything. Returns null when the URL is acceptable.
     */
    fun navigationRefusal(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.length > MAX_URL_CHARS) return "That URL is too long for the browser."
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("file:") || lower.startsWith("blob:") || lower.startsWith("intent:")
        ) {
            return "Only http and https pages can be opened."
        }
        val scheme = trimmed.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return "Only http and https pages can be opened."
        val rest = trimmed.substringAfter("://")
        var host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        // A port colon is fine on a hostname; a bracketed IPv6 literal keeps its brackets so the
        // literal test below sees the address, not "[".
        host = if (host.startsWith("[")) {
            host.substringBefore(']') + "]"
        } else {
            host.substringBefore(':')
        }
        host = host.trimEnd('.').lowercase()
        if (host.isBlank()) return "That URL has no host."
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".lan") || host == "localhost"
        ) {
            return "Local and internal hosts are not allowed."
        }
        if (isIpv4Literal(host) || isIpv6Literal(host)) {
            if (isPrivateLiteral(host)) return "Private and reserved network addresses are not allowed."
        }
        return null
    }

    /** JavaScript that scrolls by [deltaY] pixels. */
    fun scrollScript(deltaY: Int): String {
        val d = deltaY.coerceIn(-100_000, 100_000)
        return "(function(){window.scrollBy(0,$d);return 'SCROLLED';})()"
    }
}
