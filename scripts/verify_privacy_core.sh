#!/usr/bin/env bash
# Host-side verification for the egress ledger and the attestation audit.
#
# The compiler that matters for `EgressLedger.kt` is AGP, which needs the Android SDK. Three things
# can still be proven without it:
#
#   1. the policy and ledger rules, executed as a spec (Python mirror — always runs)
#   2. the same rules executed against the real Kotlin (only when kotlinc + java are present)
#   3. every socket in the tree actually asking the guard first (static audit)
#
#   ./scripts/verify_privacy_core.sh
#
# Exit code is 0 only if nothing is unguarded and no assertion fails.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEDGER="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/privacy/EgressLedger.kt"
# The Play classifier the harness also exercises. It lives in its own Android-free file, so it can be
# compiled here — it used to sit beside the Context-reading probe, which made this harness uncompilable.
PLAY_CORE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/privacy/PlayEnvironmentCore.kt"
HARNESS="$ROOT/tools/privacy-core/EgressCoreTests.kt"
SPEC="$ROOT/tools/privacy-core/spec_check.py"
AUDIT="$ROOT/tools/privacy-core/audit_egress.py"
OUT="${TMPDIR:-/tmp}/lucifer-privacy-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
have_kotlin=0
if command -v "$KOTLINC_BIN" >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  have_kotlin=1
fi

status=0

echo "== 1. egress policy + ledger spec (python mirror) =="
if python3 "$SPEC"; then
  :
else
  status=1
fi

echo
echo "== 2. egress policy + ledger (real Kotlin) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  mkdir -p "$OUT"
  if "$KOTLINC_BIN" "$LEDGER" "$PLAY_CORE" "$HARNESS" -d "$OUT/privacy-tests.jar" -nowarn; then
    STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/privacy-tests.jar:$STDLIB" com.lucifer.mobile.privacy.harness.EgressCoreTestsKt || status=1
    else
      java -cp "$OUT/privacy-tests.jar" com.lucifer.mobile.privacy.harness.EgressCoreTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the ledger" >&2
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
echo "== 2b. the ledger says who asked =="
BROWSER="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/data/browser/LuciferBrowser.kt"
CORE_SRC="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition/CognitionRunner.kt"
HEARTBEAT="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/omni_bridge/heartbeat/HeartbeatService.kt"

claim() { # file needle why
  if grep -qF -- "$2" "$1"; then echo "  ok    $3"; else echo "  MISSING  $3" >&2; echo "        ↳ expected \`$2\` in $(basename "$1")" >&2; status=1; fi
}
claim "$LEDGER" 'if (userInitiated) "" else " · on his own"' "a self-started request is marked in the log"
claim "$BROWSER" 'userInitiated: Boolean = true' "the browser takes who asked, instead of assuming"
claim "$BROWSER" 'userInitiated = userInitiated' "and passes it to the exchange that records it"
claim "$CORE_SRC" 'browser.navigate(url, userInitiated = false)' "a fetch the reasoning loop chose is his, not the owner's"
claim "$HEARTBEAT" 'userInitiated = false' "so is a heartbeat probe"
# The model route: a draft nobody asked for is not the owner's request.
LLM="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/omni_bridge/llm/NemotronClient.kt"
claim "$LLM" 'userInitiated: Boolean = true' "the model client takes who asked"
claim "$LLM" 'NetworkGuard.authorize(BASE_URL, userInitiated, expected = EgressClass.MODEL)' \
  "and records it instead of assuming the owner"
claim "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/resilience/ResilienceEngine.kt" \
  'userInitiated = false' "recovery diagnosis is the app's initiative"
claim "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/agents/android/TacticianRunner.kt" \
  'userInitiated = false' "so is a tactician pass"

echo
echo "== 3. every socket asks the guard first =="
if python3 "$AUDIT" --check; then
  :
else
  status=1
fi

echo
if [[ "$status" -eq 0 ]]; then
  echo "PASS · privacy core"
else
  echo "FAILED · privacy core" >&2
fi
exit "$status"
