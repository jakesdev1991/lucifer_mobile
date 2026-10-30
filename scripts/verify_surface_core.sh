#!/usr/bin/env bash
# Host-side verification for surface awareness.
#
# Lucifer's claims about his own interfaces have to be checkable, or "totally aware" is just a nicer
# way of saying "confident".
#
#   1. the question classifier + answer shapes, as an executable spec (python mirror — always runs)
#   2. the same rules against the real Kotlin (only when kotlinc + java are present)
#   3. a static audit: every reasoning call site must declare its Surface
#
#   ./scripts/verify_surface_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SURFACE_DIR="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/surface"
# The surface module now names the cognition core (how he thinks, and the budget in force), so that file
# must be on the compiler's path — otherwise this harness breaks only on a machine that has kotlinc.
COGNITION_CORE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition/CognitionCore.kt"
HARNESS="$ROOT/tools/surface-core/SurfaceCoreTests.kt"
SPEC="$ROOT/tools/surface-core/spec_check.py"
AUDIT="$ROOT/tools/surface-core/audit_surfaces.py"
OUT="${TMPDIR:-/tmp}/lucifer-surface-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
have_kotlin=0
if command -v "$KOTLINC_BIN" >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  have_kotlin=1
fi

status=0

echo "== 1. surface awareness spec (python mirror) =="
python3 "$SPEC" || status=1

echo
echo "== 2. surface awareness (real Kotlin) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  mkdir -p "$OUT"
  if "$KOTLINC_BIN" "$SURFACE_DIR/Surfaces.kt" "$SURFACE_DIR/SurfaceAnswers.kt" \
      "$COGNITION_CORE" "$HARNESS" \
      -d "$OUT/surface-tests.jar" -nowarn; then
    STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/surface-tests.jar:$STDLIB" com.lucifer.mobile.surface.harness.SurfaceCoreTestsKt || status=1
    else
      java -cp "$OUT/surface-tests.jar" com.lucifer.mobile.surface.harness.SurfaceCoreTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the surface module" >&2
    status=1
  fi
else
  if [[ "${LUCIFER_REQUIRE_KOTLIN:-0}" == "1" ]]; then
    echo "FAILED — LUCIFER_REQUIRE_KOTLIN=1 but kotlinc and/or java not found." >&2
    status=1
  fi
  echo "SKIPPED — kotlinc and/or java not found. The Python spec covers the same cases;"
  echo "          the Kotlin harness has NOT run in this workspace. Set KOTLINC=/path/to/kotlinc."
fi

echo
echo "== 3. every reasoning call site declares its surface =="
python3 "$AUDIT" --check || status=1

echo
if [[ "$status" -eq 0 ]]; then
  echo "PASS · surface core"
else
  echo "FAILED · surface core" >&2
fi
exit "$status"
