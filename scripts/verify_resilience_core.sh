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
# shellcheck source=scripts/android_surface.sh
source "$ROOT/scripts/android_surface.sh"
android_surface_guard
CORE_DIR="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/resilience"
HARNESS="$ROOT/tools/resilience-core/ResilienceCoreTests.kt"
STUBS="$ROOT/tools/resilience-core/android-stubs"
mapfile -t SURFACE < <(android_surface_stub_files "$STUBS")
mapfile -t JAR_ARGS < <(android_surface_classpath)
OUT="${TMPDIR:-/tmp}/lucifer-resilience-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
# A missing toolchain is "unproven", not "failed" — but it must never look like a pass either.
# CI that is expected to compile sets LUCIFER_REQUIRE_KOTLIN=1 and a missing kotlinc becomes an error.
if ! command -v "$KOTLINC_BIN" >/dev/null 2>&1; then
  if [[ "${LUCIFER_REQUIRE_KOTLIN:-0}" == "1" ]]; then
    echo "kotlinc not found and LUCIFER_REQUIRE_KOTLIN=1 — cannot verify this harness." >&2
    exit 1
  fi
  echo "SKIPPED — kotlinc not found, so the Kotlin half of this harness did NOT run."
  echo "          Set KOTLINC=/path/to/kotlinc (and JAVA_HOME) to run it, or"
  echo "          LUCIFER_REQUIRE_KOTLIN=1 to make a missing toolchain an error."
  exit 0
fi

mkdir -p "$OUT"

# "Pure core" is decided by what a file imports, not by a list of names. A file that imports `android.`
# is an adapter: it cannot compile without the Android surface, so it belongs to the type-check below,
# not to this one. The list this replaces named ResilienceEngine.kt, then also LessonTransfer.kt, and
# still missed LessonExchange.kt — the same drift, twice. Deriving it means a new adapter cannot break
# the core compile, and a core file that quietly gains an Android import fails here instead of silently
# moving itself out of the checked set.
CORE_FILES=()
ADAPTER_FILES=()
while IFS= read -r file; do
  if grep -qE '^import android\.' "$file"; then
    ADAPTER_FILES+=("$file")
  else
    CORE_FILES+=("$file")
  fi
done < <(find "$CORE_DIR" -maxdepth 1 -name '*.kt' | sort)

echo "== 0. what is core and what is an adapter =="
echo "  core     : $(printf '%s ' "${CORE_FILES[@]##*/}")"
echo "  adapters : $(printf '%s ' "${ADAPTER_FILES[@]##*/}")"
if [[ ${#CORE_FILES[@]} -eq 0 ]]; then
  echo "  FAILED  every file in resilience/ imports Android — the pure core has disappeared" >&2
  exit 1
fi

# The diagnosis sink is model prose, so the pure core now calls the output gate. Those three cognition
# files are pure Kotlin (no android imports) and are compiled from source here rather than stubbed —
# a stub of the gate would let this harness pass while the real gate did something else.
GATE_FILES=(
  "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition/CognitionCore.kt"
  "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition/AnswerAudit.kt"
  "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition/OutputGate.kt"
  "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition/OutputBoundary.kt"
)
for file in "${GATE_FILES[@]}"; do
  if grep -qE '^import android\.' "$file"; then
    echo "  FAILED  ${file##*/} imports Android, so it cannot belong to a pure-core compile" >&2
    exit 1
  fi
done

echo "== compile + run pure core checks =="
"$KOTLINC_BIN" "${CORE_FILES[@]}" "${GATE_FILES[@]}" "$HARNESS" -d "$OUT/resilience-tests.jar" -nowarn

STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
if [[ -f "$STDLIB" ]]; then
  java -cp "$OUT/resilience-tests.jar:$STDLIB" com.lucifer.mobile.resilience.harness.ResilienceCoreTestsKt
else
  java -cp "$OUT/resilience-tests.jar" com.lucifer.mobile.resilience.harness.ResilienceCoreTestsKt
fi

echo
echo "== type-check the Android adapter =="
android_surface_report "$STUBS"
# The adapters are compiled with the core they sit on, the config package they read through (every
# `files/config/` path is named once in AppConfig) and nothing else. This is the first time
# LessonTransfer.kt has been compiled by anything — it was excluded from this check by name as well.
"$KOTLINC_BIN" ${JAR_ARGS[@]+"${JAR_ARGS[@]}"} "${SURFACE[@]}" \
  "${CORE_FILES[@]}" "${ADAPTER_FILES[@]}" "${GATE_FILES[@]}" \
  "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/config/AppConfig.kt" \
  -d "$OUT/engine-check" -nowarn
echo "adapter type-check passed (the Android/JVM surface only)"
