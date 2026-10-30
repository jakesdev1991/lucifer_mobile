#!/usr/bin/env python3
"""Executable spec for the interactive-browser policy.

`BrowserPolicy.kt` decides which browser actions need a human and whether the WebView may run at all
under the current transport. Both are privacy rules, so both are written a second time here and
executed:

    python3 tools/browser-core/spec_check.py

`BrowserCoreTests.kt` asserts the same cases against the real Kotlin. A mismatch is a bug in one of
them, never a pass.

Exit code is 0 only when every case holds.
"""

from __future__ import annotations

import os
import re

passed = 0
failures: list[str] = []


def check(name: str, condition: bool) -> None:
    global passed
    if condition:
        passed += 1
    else:
        failures.append(name)


def expect_eq(name: str, actual, expected) -> None:
    check(f"{name} (got {actual!r}, want {expected!r})", actual == expected)


# ── mirror of BrowserPolicy ──────────────────────────────────────────
MAX_URL_CHARS = 2048
MAX_SELECTOR_CHARS = 300
MAX_TYPE_CHARS = 2000
MAX_SCRIPT_CHARS = 4000

READS = ("NAVIGATE", "READ", "SCREENSHOT")
MUTATORS = ("CLICK", "TYPE", "EVALUATE")


def availability(transport: str, system_vpn: bool):
    if transport.upper() != "TOR":
        return (True, False, "WebView opens its own sockets, outside the ledger.")
    if system_vpn:
        return (True, False, "System VPN present · WebView traffic is caught at the IP layer, so Tor mode covers it.")
    return (False, False,
            "Tor is selected and no system VPN is running. A WebView opens its own sockets: it would "
            "bypass Lucifer's tunnel and never appear in the ledger, which is exactly the leak Tor "
            "mode exists to prevent. Run Orbot's VPN mode (or WireGuard) to browse here, or switch "
            "the transport to DIRECT and accept that these page requests are direct.")


def decide(action: str, target: str, available: bool = True):
    if not available:
        return (False, False, "The interactive browser is not available in this transport.")
    clean = target.strip()

    if action == "NAVIGATE":
        if len(clean) > MAX_URL_CHARS:
            return (False, False, "That URL is too long for the browser.")
        if not clean:
            return (False, False, "Enter a URL first.")
        lower = clean.lower()
        for scheme in ("javascript:", "data:", "file:", "blob:"):
            if lower.startswith(scheme):
                return (False, False, "Only http and https pages can be opened.")

    if action in ("CLICK", "TYPE", "EVALUATE"):
        if not clean:
            return (False, False, "Nothing to act on.")
        limit = {"CLICK": MAX_SELECTOR_CHARS, "TYPE": MAX_TYPE_CHARS, "EVALUATE": MAX_SCRIPT_CHARS}[action]
        if len(clean) > limit:
            return (False, False, f"That is longer than {limit} characters.")

    if action in READS:
        return (True, False, "")
    if action == "CLICK":
        return (True, True, "A click is not a read: it can post, message, buy or delete. Confirm it first.")
    if action == "TYPE":
        return (True, True, "Text typed into a page is data you are giving it. Confirm it first.")
    return (True, True, "A script can do anything the page can do. Confirm it first.")


SELECTOR_RE = re.compile(r"^[A-Za-z0-9#._\-\[\]=\"'*>:+~(),/\s]+$")


def is_valid_selector(selector: str) -> bool:
    s = selector.strip()
    if not s or len(s) > MAX_SELECTOR_CHARS:
        return False
    if s.lower().startswith("javascript:"):
        return False
    if not SELECTOR_RE.match(s):
        return False
    return not any(ch in s for ch in ("\\", "`", "$", '"'))


def js_string(raw: str) -> str:
    out = ["'"]
    for ch in raw:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "'":
            out.append("\\'")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "<":
            out.append("\\u003c")
        elif ch == ">":
            out.append("\\u003e")
        else:
            out.append(ch)
    out.append("'")
    return "".join(out)


def unescaped_quotes(literal: str) -> int:
    """Quotes that would actually terminate the JS string: not preceded by an odd run of backslashes."""
    count = 0
    index = 0
    while index < len(literal):
        if literal[index] == "'":
            backslashes = 0
            probe = index - 1
            while probe >= 0 and literal[probe] == "\\":
                backslashes += 1
                probe -= 1
            if backslashes % 2 == 0:
                count += 1
        index += 1
    return count


def click_script(selector: str) -> str:
    return ("(function(){var el=document.querySelector(" + js_string(selector.strip()) + ");"
            "if(!el){return 'NO_MATCH';}el.click();return 'CLICKED';})()")


def type_script(selector: str, text: str) -> str:
    return ("(function(){var el=document.querySelector(" + js_string(selector.strip()) + ");"
            "if(!el){return 'NO_MATCH';}el.focus();el.value=" + js_string(text) + ";"
            "el.dispatchEvent(new Event('input',{bubbles:true}));"
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'TYPED';})()")


# ── cases ────────────────────────────────────────────────────────────
def case_availability() -> None:
    ok, confirm, _ = availability("DIRECT", False)
    check("direct transport allows the browser", ok)
    check("availability itself never asks for confirmation", not confirm)

    ok, _, reason = availability("TOR", True)
    check("tor with a system vpn allows the browser", ok)
    check("tor + vpn explains why it is safe", "IP layer" in reason)

    ok, _, reason = availability("TOR", False)
    check("tor without a vpn refuses the browser", not ok)
    check("the refusal names the leak", "bypass Lucifer's tunnel" in reason)
    check("the refusal offers the two real options", "Orbot's VPN mode" in reason and "DIRECT" in reason)

    ok, _, _ = availability("tor", True)
    check("transport compare is case-insensitive", ok)


def case_actions() -> None:
    for action in READS:
        allowed, confirm, _ = decide(action, "https://example.com")
        check(f"{action} is allowed", allowed)
        check(f"{action} needs no confirmation", not confirm)

    for action in MUTATORS:
        allowed, confirm, reason = decide(action, "#submit-button" if action == "CLICK" else "hello")
        check(f"{action} is allowed with confirmation", allowed and confirm)
        check(f"{action} says why", "Confirm it first" in reason)

    check("click is described as not a read", "not a read" in decide("CLICK", "#a")[2])
    check("typing is described as giving data away", "data you are giving" in decide("TYPE", "x")[2])

    # Schemes that are not pages.
    for url in ("javascript:alert(1)", "data:text/html,<b>x", "file:///etc/passwd", "blob:https://x/y", "JAVASCRIPT:x"):
        allowed, _, reason = decide("NAVIGATE", url)
        check(f"navigate refuses {url}", not allowed and "http and https" in reason)

    check("http is accepted", decide("NAVIGATE", "http://example.com")[0])
    check("https is accepted", decide("NAVIGATE", "https://example.com")[0])
    check("blank url is refused", not decide("NAVIGATE", "   ")[0])
    check("over-long url is refused", not decide("NAVIGATE", "https://x/" + "a" * MAX_URL_CHARS)[0])
    check("blank click target is refused", not decide("CLICK", "  ")[0])
    check("over-long script is refused", not decide("EVALUATE", "x" * (MAX_SCRIPT_CHARS + 1))[0])

    allowed, confirm, _ = decide("CLICK", "#x", available=False)
    check("nothing is allowed when the browser is unavailable", not allowed and not confirm)


def case_selectors() -> None:
    for good in ("#submit", ".btn.primary", "form > input[name=email]", "a[href='/next']", "div#main .item"):
        check(f"selector {good!r} is accepted", is_valid_selector(good))
    for bad in ("", "   ", "javascript:alert(1)", "a'); alert(1); ('", 'input[name="a"]',
                "#x`", "$(x)", "x" * (MAX_SELECTOR_CHARS + 1)):
        check(f"selector {bad[:18]!r} is refused", not is_valid_selector(bad))


def case_js_escaping() -> None:
    expect_eq("plain string is quoted", js_string("abc"), "'abc'")
    expect_eq("single quote is escaped", js_string("it's"), "'it\\'s'")
    expect_eq("backslash is escaped", js_string("a\\b"), "'a\\\\b'")
    expect_eq("newline is escaped", js_string("a\nb"), "'a\\nb'")
    expect_eq("angle brackets are unicode-escaped", js_string("<script>"), "'\\u003cscript\\u003e'")
    escaped = js_string("'); alert(1); ('")
    check("a quote cannot break out of the literal", unescaped_quotes(escaped) == 2)
    check("a script tag cannot survive a type action", "<script" not in type_script("#q", "<script>alert(1)</script>"))

    script = click_script("#go")
    check("click script queries the selector", "document.querySelector('#go')" in script)
    check("click script reports a miss instead of guessing", "NO_MATCH" in script)
    typed = type_script("#email", "a@b.co")
    check("type script sets the value", "el.value='a@b.co'" in typed)
    check("type script fires input", "new Event('input'" in typed)
    check("type script fires change", "new Event('change'" in typed)


def _strip_comments(source: str) -> str:
    """Remove // line comments and /* … */ blocks (KDoc included) without touching string literals."""
    out = []
    i = 0
    n = len(source)
    while i < n:
        two = source[i:i + 2]
        if two == "//":
            while i < n and source[i] != "\n":
                i += 1
        elif two == "/*":
            i += 2
            while i < n and source[i:i + 2] != "*/":
                i += 1
            i += 2
        elif source[i] == '"':
            out.append(source[i])
            i += 1
            while i < n:
                if source[i] == "\\" and i + 1 < n:
                    out.append(source[i:i + 2])
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == '"':
                    i += 1
                    break
                i += 1
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def case_drift() -> None:
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(root, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile",
                        "browser", "BrowserPolicy.kt")
    body = open(path, encoding="utf-8").read()

    for name, value in (
        ("MAX_URL_CHARS", 2048), ("MAX_SELECTOR_CHARS", 300),
        ("MAX_TYPE_CHARS", 2000), ("MAX_SCRIPT_CHARS", 4000),
    ):
        plain = str(value)
        underscored = f"{value:,}".replace(",", "_")
        check(f"drift: {name} is {value}",
              f"const val {name} = {plain}" in body or f"const val {name} = {underscored}" in body)

    for action in ("NAVIGATE", "READ", "SCREENSHOT", "CLICK", "TYPE", "EVALUATE"):
        check(f"drift: {action} still exists", f"{action}(" in body)
    for scheme in ("javascript:", "data:", "file:", "blob:"):
        check(f"drift: {scheme} is still refused", scheme in body)
    check("drift: mutators still require confirmation", body.count("true,\n                true,") >= 1 or "needsConfirmation = true" in body or "Decision(\n                true,\n                true," in body)
    check("drift: the WebView refusal still names the tunnel", "bypass Lucifer's tunnel" in body)

    # The interactive browser must not be the thing that opens a socket outside the gate.
    interactive = os.path.join(root, "composeApp", "src", "androidMain", "kotlin", "com", "lucifer", "mobile",
                               "browser", "InteractiveBrowser.kt")
    if os.path.isfile(interactive):
        kt = open(interactive, encoding="utf-8").read()
        # Comments describe the design ("a WebView never asks EgressHttp"), so they must not count as
        # call sites. Strip line comments and the KDoc block before looking for real socket use.
        code = _strip_comments(kt)
        check("drift: InteractiveBrowser opens no socket of its own",
              "Socket(" not in code and "openConnection" not in code and "EgressHttp" not in code)
        check("drift: InteractiveBrowser documents that WebView is not in the ledger",
              "ledger" in kt.lower())


def main() -> int:
    case_availability()
    case_actions()
    case_selectors()
    case_js_escaping()
    case_drift()

    for line in failures:
        print(f"FAIL  {line}")
    print()
    if failures:
        print(f"FAILED · {passed} passed, {len(failures)} failed")
        return 1
    print(f"PASS · {passed} checks (interactive browser policy spec)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
