#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC:-kotlinc}"
if ! command -v "$KOTLINC_BIN" >/dev/null || ! command -v java >/dev/null; then
  if [[ "${LUCIFER_REQUIRE_KOTLIN:-0}" == 1 ]]; then
    echo 'FAILED — Kotlin/JDK required'; exit 1
  fi
  echo 'SKIPPED — Kotlin/JDK unavailable'; exit 0
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC_BIN" \
  "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/omni_bridge/llm/RuntimeKeys.kt" \
  "$ROOT/tools/credentials-core/RuntimeKeysTests.kt" -d "$OUT/tests.jar" -nowarn
STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
java -cp "$OUT/tests.jar:$STDLIB" com.lucifer.mobile.omni_bridge.llm.RuntimeKeysTestsKt
