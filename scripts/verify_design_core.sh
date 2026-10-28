#!/usr/bin/env bash
# Host-side verification for the Morningstar design math.
#
# The Compose layer needs the Android SDK, which is not available here, but the geometry that the
# interface is built on — 45° chamfer outlines, the sigil's compass arcs, the ember field — is pure
# Kotlin. This compiles those with an assertion harness and checks the bounds.
#
# Requires: java (17+) and kotlinc, or set KOTLINC/JAVA_HOME.
#
#   ./scripts/verify_design_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THEME_DIR="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/theme"
HARNESS="$ROOT/tools/design-core/DesignCoreTests.kt"
OUT="${TMPDIR:-/tmp}/lucifer-design-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
if ! command -v "$KOTLINC_BIN" >/dev/null 2>&1; then
  echo "kotlinc not found. Set KOTLINC=/path/to/kotlinc (and JAVA_HOME) to run this harness." >&2
  exit 127
fi

mkdir -p "$OUT"

echo "== compile + run Morningstar design checks =="
"$KOTLINC_BIN" "$THEME_DIR/MorningstarGeometry.kt" "$HARNESS" \
  -d "$OUT/design-tests.jar" -nowarn

STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
if [[ -f "$STDLIB" ]]; then
  java -cp "$OUT/design-tests.jar:$STDLIB" com.lucifer.mobile.ui.theme.harness.DesignCoreTestsKt
else
  java -cp "$OUT/design-tests.jar" com.lucifer.mobile.ui.theme.harness.DesignCoreTestsKt
fi
