#!/usr/bin/env bash
# Host-side verification for the skill synthesizer.
#
# "Everything he does improves him" is only true if the rules that decide *what* he learns can be
# executed and inspected. They are pure Kotlin, so they can be.
#
#   1. the detector, the safety gate, promotion/retirement and prompt rendering, as an executable spec
#      (python mirror — always runs)
#   2. the identical cases against the real Kotlin (only when kotlinc + java are present)
#   3. a static check that the Kotlin core still touches no network, no files and no Android types
#
#   ./scripts/verify_synth_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile"
SYNTH_DIR="$SRC/synth"
JSON="$SRC/resilience/JsonLite.kt"
HARNESS="$ROOT/tools/synth-core/SkillSynthCoreTests.kt"
SPEC="$ROOT/tools/synth-core/spec_check.py"
OUT="${TMPDIR:-/tmp}/lucifer-synth-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
have_kotlin=0
if command -v "$KOTLINC_BIN" >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  have_kotlin=1
fi

status=0

echo "== 1. skill synthesizer spec (python mirror) =="
python3 "$SPEC" || status=1

echo
echo "== 2. skill synthesizer (real Kotlin) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  mkdir -p "$OUT"
  if "$KOTLINC_BIN" "$JSON" "$SYNTH_DIR/SkillSynthModel.kt" "$SYNTH_DIR/FeedbackDetector.kt" \
      "$SYNTH_DIR/SkillSynth.kt" "$HARNESS" -d "$OUT/synth-tests.jar" -nowarn; then
    STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/synth-tests.jar:$STDLIB" com.lucifer.mobile.synth.harness.SkillSynthCoreTestsKt || status=1
    else
      java -cp "$OUT/synth-tests.jar" com.lucifer.mobile.synth.harness.SkillSynthCoreTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the synth module" >&2
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
echo "== 3. the core is pure (no network, no files, no Android) =="
if grep -nE '\b(java\.net|openConnection|File\(|Context|android\.)' "$SYNTH_DIR"/*.kt; then
  echo "The synthesizer core must stay pure so it can be tested on a host." >&2
  status=1
else
  echo "OK · synth core has no Android, file or network imports"
fi

echo
if [[ "$status" -eq 0 ]]; then
  echo "PASS · synth core"
else
  echo "FAILED · synth core" >&2
fi
exit "$status"
