package com.lucifer.mobile.browser.harness

import com.lucifer.mobile.browser.BrowserAction
import com.lucifer.mobile.browser.BrowserPolicy

/**
 * Executes the interactive-browser rules against the real Kotlin.
 *
 * `tools/browser-core/spec_check.py` mirrors these same cases in Python and runs everywhere;
 * this harness runs only when a JDK and kotlinc are present, and it exists so the mirror cannot
 * drift from the shipped code without something failing.
 *
 * Not a unit-test framework: no dependency, no discovery, just a `main` that counts.
 */
object BrowserCoreTests {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
        } else {
            failed++
            println("  FAIL  $name${if (detail.isEmpty()) "" else " — $detail"}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Lucifer browser core · Kotlin harness")

        // ── availability: the WebView/Tor question ──
        check("direct is available", BrowserPolicy.availability("DIRECT", false).allowed)
        check("tor without vpn is refused", !BrowserPolicy.availability("TOR", false).allowed)
        check("tor with system vpn is available", BrowserPolicy.availability("TOR", true).allowed)
        check(
            "tor refusal names the reason",
            BrowserPolicy.availability("TOR", false).reason.contains("own sockets")
        )
        check(
            "transport match is case-insensitive",
            !BrowserPolicy.availability("tor", false).allowed
        )

        // ── reads free, mutations confirmed ──
        for (read in listOf(BrowserAction.NAVIGATE, BrowserAction.READ, BrowserAction.SCREENSHOT)) {
            val d = BrowserPolicy.decide(read, "https://example.com")
            check("${read.name} allowed", d.allowed)
            check("${read.name} needs no confirmation", !d.needsConfirmation)
        }
        for (write in listOf(BrowserAction.CLICK, BrowserAction.TYPE, BrowserAction.EVALUATE)) {
            val target = when (write) {
                BrowserAction.CLICK -> "#submit"
                BrowserAction.TYPE -> "hello"
                else -> "document.title"
            }
            val d = BrowserPolicy.decide(write, target)
            check("${write.name} allowed", d.allowed)
            check("${write.name} requires confirmation", d.needsConfirmation, d.reason)
            check("${write.name} explains itself", d.reason.isNotBlank())
        }

        // ── unavailable browser refuses everything ──
        for (action in BrowserAction.values()) {
            val d = BrowserPolicy.decide(action, "https://example.com", available = false)
            check("${action.name} refused when unavailable", d.refused && !d.needsConfirmation)
        }

        // ── schemes that are not the web ──
        for (scheme in listOf("javascript:", "data:", "file:", "blob:")) {
            val d = BrowserPolicy.decide(BrowserAction.NAVIGATE, "${scheme}alert(1)")
            check("navigate refuses $scheme", d.refused, d.reason)
        }
        check(
            "scheme check ignores case",
            BrowserPolicy.decide(BrowserAction.NAVIGATE, "JavaScript:alert(1)").refused
        )
        check("blank url refused", BrowserPolicy.decide(BrowserAction.NAVIGATE, "   ").refused)
        check(
            "over-long url refused",
            BrowserPolicy.decide(
                BrowserAction.NAVIGATE,
                "https://example.com/" + "a".repeat(BrowserPolicy.MAX_URL_CHARS)
            ).refused
        )

        // ── length ceilings ──
        check(
            "over-long selector refused",
            BrowserPolicy.decide(
                BrowserAction.CLICK,
                "#" + "a".repeat(BrowserPolicy.MAX_SELECTOR_CHARS)
            ).refused
        )
        check(
            "over-long typed text refused",
            BrowserPolicy.decide(
                BrowserAction.TYPE,
                "x".repeat(BrowserPolicy.MAX_TYPE_CHARS + 1)
            ).refused
        )
        check(
            "over-long script refused",
            BrowserPolicy.decide(
                BrowserAction.EVALUATE,
                "x".repeat(BrowserPolicy.MAX_SCRIPT_CHARS + 1)
            ).refused
        )
        check("blank click target refused", BrowserPolicy.decide(BrowserAction.CLICK, "  ").refused)

        // ── selectors ──
        for (good in listOf("#login", ".btn-primary", "input[name=email]", "form > input", "a[href='/x']")) {
            check("selector accepted: $good", BrowserPolicy.isValidSelector(good))
        }
        for (bad in listOf("", "   ", "div)", "a{color:red}", "'; alert(1); //", "a`b", "a\$b", "a\"b", "javascript:x")) {
            check("selector rejected: '$bad'", !BrowserPolicy.isValidSelector(bad))
        }

        // ── JS escaping: nothing can leave the string literal ──
        val nasty = "a'b\\c\nd\re<f>g"
        val escaped = BrowserPolicy.jsString(nasty)
        check("js string is single-quoted", escaped.startsWith("'") && escaped.endsWith("'"))
        check("js string escapes the quote", escaped.contains("\\'") && !escaped.contains("a'b"))
        check("js string escapes the backslash", escaped.contains("\\\\"))
        check("js string has no literal newline", !escaped.contains("\n") && !escaped.contains("\r"))
        check("js string neutralises angle brackets", escaped.contains("\\u003c") && escaped.contains("\\u003e"))

        // A payload shaped like a breakout must not produce a closing quote of its own.
        val breakout = "'); fetch('https://evil.example'); ('"
        val quoted = BrowserPolicy.jsString(breakout)
        // Every quote inside the payload must be escaped: remove escaped pairs, and only the
        // wrapping quotes may remain. If this number is not 2, the literal can be closed early.
        val unescapedQuotes = quoted.replace("\\'", "").count { it == '\'' }
        check("breakout payload stays inside one literal", unescapedQuotes == 2, quoted)

        val clickScript = BrowserPolicy.clickScript("#go")
        check("click script queries the selector", clickScript.contains("querySelector('#go')"))
        check("click script reports NO_MATCH", clickScript.contains("NO_MATCH"))
        val typeScript = BrowserPolicy.typeScript("#q", "hi there")
        check("type script sets the value", typeScript.contains("el.value='hi there'"))
        check("type script fires input and change", typeScript.contains("'input'") && typeScript.contains("'change'"))

        // ── describe(): the confirmation sheet names the action ──
        check("describe click names the target", BrowserPolicy.describe(BrowserAction.CLICK, "#buy").contains("#buy"))
        check(
            "describe type counts the characters",
            BrowserPolicy.describe(BrowserAction.TYPE, "12345").contains("5")
        )
        check(
            "describe evaluate counts the characters",
            BrowserPolicy.describe(BrowserAction.EVALUATE, "abc").contains("3")
        )

        println()
        if (failed == 0) {
            println("PASS · $passed checks")
        } else {
            println("FAIL · $passed passed, $failed failed")
            kotlin.system.exitProcess(1)
        }
    }
}
