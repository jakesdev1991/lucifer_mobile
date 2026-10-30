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
    EVALUATE("evaluate")
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
            BrowserAction.NAVIGATE, BrowserAction.READ, BrowserAction.SCREENSHOT ->
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
     * Deliberately narrow: `#id`, `.class`, `tag`, `[attr=value]` and simple descendant chains. It is
     * interpolated into JavaScript, so the character set is the safety boundary — no quotes, no
     * backslashes, no whitespace beyond a single descendant space.
     */
    fun isValidSelector(selector: String): Boolean {
        val s = selector.trim()
        if (s.isEmpty() || s.length > MAX_SELECTOR_CHARS) return false
        if (s.lowercase().startsWith("javascript:")) return false
        val allowed = Regex("^[A-Za-z0-9#._\\-\\[\\]=\"'*>:+~(),/\\s]+$")
        if (!allowed.matches(s)) return false
        // Nothing that could close the JS string it is embedded in.
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
        else -> action.label
    }
}
