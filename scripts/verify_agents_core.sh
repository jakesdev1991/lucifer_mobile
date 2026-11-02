#!/usr/bin/env bash
# Host-side verification for Lucifer's agent ecosystem and the General/tactician core.
#
# The Android app cannot be built in this workspace (no Android SDK), but the prompt rules, agent-file
# parser, composer, observation ledger, proposal detectors, and tactician loop are pure Kotlin. This
# script compiles them with an assertion harness, runs the checks, and then type-checks the Android
# adapters (prompt store, observation bus, signal collector, runner, service, heartbeat engine)
# against small stubs.
#
# Requires: java (17+) and kotlinc, or set KOTLINC/JAVA_HOME.
#
#   ./scripts/verify_agents_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/android_surface.sh
source "$ROOT/scripts/android_surface.sh"
android_surface_guard
SRC="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile"
RESILIENCE_DIR="$SRC/resilience"
AGENTS_DIR="$SRC/agents"
TACTICIAN_DIR="$SRC/tactician"
ADAPTER_DIR="$SRC/agents/android"
HARNESS="$ROOT/tools/agents-core/AgentCoreTests.kt"
STUBS="$ROOT/tools/agents-core/android-stubs"
mapfile -t SURFACE < <(android_surface_stub_files "$STUBS")
mapfile -t JAR_ARGS < <(android_surface_classpath)
OUT="${TMPDIR:-/tmp}/lucifer-agents-build"

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

# The resilience package is a shared dependency (JSON helper, redaction, lesson ledger). Its Android
# adapter pulls in the whole app, so it is excluded here.
# Same rule as verify_resilience_core.sh: an `android.` import makes a file an adapter, not core. The
# name list this replaces missed LessonExchange.kt and the core compile broke on it.
CORE_FILES=()
ADAPTER_FILES=()
while IFS= read -r file; do
  if grep -qE '^import android\.' "$file"; then
    ADAPTER_FILES+=("$file")
  else
    CORE_FILES+=("$file")
  fi
done < <(find "$RESILIENCE_DIR" -maxdepth 1 -name '*.kt' | sort)

echo "== compile + run agent ecosystem checks =="
"$KOTLINC_BIN" "${CORE_FILES[@]}" "$AGENTS_DIR"/*.kt "$TACTICIAN_DIR"/*.kt "$HARNESS" \
  -d "$OUT/agents-tests.jar" -nowarn

STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
if [[ -f "$STDLIB" ]]; then
  java -cp "$OUT/agents-tests.jar:$STDLIB" com.lucifer.mobile.agents.harness.AgentCoreTestsKt
else
  java -cp "$OUT/agents-tests.jar" com.lucifer.mobile.agents.harness.AgentCoreTestsKt
fi

echo
echo "== type-check the Android adapters =="
android_surface_report "$STUBS"
# `TacticianRunner` reaches SkillSynthRuntime, which needs the config/synth/skills stack. Those are
# compiled from source, not stubbed: the hand-written `com.lucifer.mobile.skills.SkillSynthRuntime` stub
# had only `learnProcedure`, so main's new `learnProposalOutcome` call broke the type-check — the same
# drift that made the privilege stubs lie, in Lucifer's own package this time. Real sources cannot drift
# from themselves.
# Only the resilience *core* comes along. Its adapters (ResilienceEngine, LessonTransfer) reach the
# launcher, the terminal and flows, which this harness has no stubs for and no need of — the resilience
# harness type-checks them against the right surface. Keeping them out here is what keeps this compile
# about agents.
"$KOTLINC_BIN" ${JAR_ARGS[@]+"${JAR_ARGS[@]}"} "${SURFACE[@]}" "${CORE_FILES[@]}" "$AGENTS_DIR"/*.kt "$TACTICIAN_DIR"/*.kt \
  "$ADAPTER_DIR"/*.kt "$SRC/omni_bridge/heartbeat/HeartbeatService.kt" \
  "$SRC/config/AppConfig.kt" "$SRC/synth"/*.kt "$SRC/skills/SkillSynthRuntime.kt" \
  "$SRC/cognition/OutputGate.kt" "$SRC/cognition/AnswerAudit.kt" "$SRC/cognition/CognitionCore.kt" \
  -d "$OUT/adapters-check" -nowarn
echo "adapter type-check passed (the Android/JVM surface only; UI and runtime unverified)"
