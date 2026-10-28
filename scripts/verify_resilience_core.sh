#!/usr/bin/env bash
# Host-side verification for Lucifer's pure resilience core.
#
# The Android app itself cannot be built in a workspace without the Android SDK, but the
# classification, learning, diagnosis, research, and planning logic is pure Kotlin. This script
# compiles it together with an assertion harness, runs the checks, and type-checks the Android
# adapter against small stubs.
#
# Requires: java (17+) and kotlinc, or set KOTLINC/JAVA_HOME.
#
#   ./scripts/verify_resilience_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/resilience"
HARNESS="$ROOT/tools/resilience-core/ResilienceCoreTests.kt"
STUBS="$ROOT/tools/resilience-core/android-stubs"
OUT="${TMPDIR:-/tmp}/lucifer-resilience-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
if ! command -v "$KOTLINC_BIN" >/dev/null 2>&1; then
  echo "kotlinc not found. Set KOTLINC=/path/to/kotlinc (and JAVA_HOME) to run this harness." >&2
  exit 127
fi

mkdir -p "$OUT"

# Everything in the resilience package except the Android adapter is pure Kotlin.
CORE_FILES=()
while IFS= read -r file; do
  case "$(basename "$file")" in
    ResilienceEngine.kt) continue ;;
    *) CORE_FILES+=("$file") ;;
  esac
done < <(find "$CORE_DIR" -maxdepth 1 -name '*.kt' | sort)

echo "== compile + run pure core checks =="
"$KOTLINC_BIN" "${CORE_FILES[@]}" "$HARNESS" -d "$OUT/resilience-tests.jar" -nowarn

STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
if [[ -f "$STDLIB" ]]; then
  java -cp "$OUT/resilience-tests.jar:$STDLIB" com.lucifer.mobile.resilience.harness.ResilienceCoreTestsKt
else
  java -cp "$OUT/resilience-tests.jar" com.lucifer.mobile.resilience.harness.ResilienceCoreTestsKt
fi

echo
echo "== type-check the Android adapter against stubs =="
"$KOTLINC_BIN" "$STUBS"/*.kt "${CORE_FILES[@]}" "$CORE_DIR/ResilienceEngine.kt" -d "$OUT/engine-check" -nowarn
echo "adapter type-check passed (stubs cover Android/JVM surface only)"
