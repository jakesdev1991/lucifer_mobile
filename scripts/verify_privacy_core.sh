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
  if "$KOTLINC_BIN" "$LEDGER" "$HARNESS" -d "$OUT/privacy-tests.jar" -nowarn; then
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
  echo "SKIPPED — kotlinc and/or java not found. The Python spec above covers the same cases;"
  echo "          the Kotlin harness has NOT run in this workspace. Set KOTLINC=/path/to/kotlinc."
fi

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
