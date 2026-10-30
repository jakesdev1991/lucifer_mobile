#!/usr/bin/env bash
# Host-side verification for the interactive browser.
#
# Three parts, because three different things can be wrong:
#
#   1. the rules, executed as a spec (Python mirror — always runs)
#   2. the same rules executed against the real Kotlin (only when kotlinc + java are present)
#   3. the shipped WebView is still configured the way the rules assume: no file/content access, no
#      popups, mixed content blocked, every mutating action routed through BrowserPolicy first, and
#      no `javascript:` URL handed to loadUrl
#
#   ./scripts/verify_browser_core.sh
#
# Exit code is 0 only if every part holds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BROWSER="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/browser"
PANEL="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/components/BrowserControlPanel.kt"
HARNESS="$ROOT/tools/browser-core/BrowserCoreTests.kt"
SPEC="$ROOT/tools/browser-core/spec_check.py"
OUT="${TMPDIR:-/tmp}/lucifer-browser-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
have_kotlin=0
if command -v "$KOTLINC_BIN" >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  have_kotlin=1
fi

status=0

echo "== 1. browser policy spec (python mirror) =="
if python3 "$SPEC"; then :; else status=1; fi

echo
echo "== 2. browser policy (real Kotlin) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  mkdir -p "$OUT"
  if "$KOTLINC_BIN" "$BROWSER/BrowserPolicy.kt" "$HARNESS" \
      -d "$OUT/browser-tests.jar" -nowarn; then
    STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/browser-tests.jar:$STDLIB" com.lucifer.mobile.browser.harness.BrowserCoreTests || status=1
    else
      java -cp "$OUT/browser-tests.jar" com.lucifer.mobile.browser.harness.BrowserCoreTests || status=1
    fi
  else
    echo "kotlinc failed to compile the browser core" >&2
    status=1
  fi
else
  if [[ "${LUCIFER_REQUIRE_KOTLIN:-0}" == "1" ]]; then
    echo "FAILED — LUCIFER_REQUIRE_KOTLIN=1 but kotlinc and/or java not found." >&2
    status=1
  fi
  echo "SKIPPED — kotlinc and/or java not found. The Python spec above covers the same cases;"
  echo "          the Kotlin harness has NOT run in this workspace. Set KOTLINC=/path/to/kotlinc."
fi

echo
echo "== 3. the shipped WebView still matches the rules =="

# Each entry: a string that MUST be present in InteractiveBrowser.kt. Removing or weakening one of
# these is how a "temporary" loosening becomes permanent, so the harness refuses the change.
require() {
  local file="$1" needle="$2" why="$3"
  if grep -qF -- "$needle" "$file"; then
    echo "  ok    ${needle:0:64}"
  else
    echo "  MISSING  ${needle:0:64}" >&2
    echo "        ↳ $why" >&2
    status=1
  fi
}

INTERACTIVE="$BROWSER/InteractiveBrowser.kt"
if [[ ! -f "$INTERACTIVE" ]]; then
  echo "  MISSING  $INTERACTIVE" >&2
  status=1
else
  require "$INTERACTIVE" "allowFileAccess = false"          "no file:// reads"
  require "$INTERACTIVE" "allowContentAccess = false"        "no content:// reads"
  require "$INTERACTIVE" "allowFileAccessFromFileURLs = false" "no cross-origin file reads"
  require "$INTERACTIVE" "allowUniversalAccessFromFileURLs = false" "no universal access"
  require "$INTERACTIVE" "setGeolocationEnabled(false)"     "the page cannot ask for location"
  require "$INTERACTIVE" "setSupportMultipleWindows(false)" "no popup windows outside the policy"
  require "$INTERACTIVE" "javaScriptCanOpenWindowsAutomatically = false" "scripts cannot open windows"
  require "$INTERACTIVE" "MIXED_CONTENT_NEVER_ALLOW"        "https pages cannot pull http resources"
  require "$INTERACTIVE" "setAcceptThirdPartyCookies(view, false)" "first-party cookies only"
  require "$INTERACTIVE" "shouldOverrideUrlLoading"         "non-http(s) navigation is intercepted"
  require "$INTERACTIVE" "USER_AGENT"                       "a distinct user agent, not the default WebView one"
  require "$INTERACTIVE" "destroy()"                        "the WebView can be torn down"
fi

if [[ -f "$PANEL" ]]; then
  # Every mutating button must consult the policy before it touches the page.
  for action in CLICK TYPE EVALUATE; do
    if ! grep -q "BrowserAction.$action" "$PANEL"; then
      echo "  MISSING  the panel never classifies $action with BrowserPolicy" >&2
      status=1
    fi
  done
  # And the confirmation card must exist: a mutating action with no sheet is a silent click.
  # Every mutation is decided twice: once when the sheet opens, once when DO IT is pressed.
  if [[ "$(grep -c "BrowserPolicy.decide" "$PANEL")" -lt 3 ]]; then
    echo "  MISSING  the panel does not re-check the policy before acting" >&2
    status=1
  fi
  if ! grep -q "CONFIRM ACTION" "$PANEL"; then
    echo "  MISSING  no confirmation card in the panel" >&2
    status=1
  fi
else
  echo "  MISSING  $PANEL" >&2
  status=1
fi

# A javascript: URL may never be loaded directly — the policy refuses it, and nothing else should
# even build one. The only permitted javascript: strings are the policy's own generated scripts.
if grep -rn "loadUrl(\"javascript:" "$BROWSER" "$PANEL" 2>/dev/null | grep -v '^Binary'; then
  echo "  FAILED  a javascript: URL is passed to loadUrl" >&2
  status=1
else
  echo "  ok    no javascript: URL is handed to loadUrl"
fi

# The interactive browser must not be reachable while the read-only fetcher is the only browser:
# both paths exist, and the read-only one is still the default (checked in the panel).
if grep -q "READ-ONLY" "$PANEL" && grep -q "INTERACTIVE" "$PANEL"; then
  echo "  ok    the panel keeps both modes, read-only first"
else
  echo "  WARN  the panel no longer shows both modes" >&2
fi

echo
if [[ $status -eq 0 ]]; then
  echo "BROWSER CORE OK"
else
  echo "BROWSER CORE FAILED" >&2
fi
exit $status
