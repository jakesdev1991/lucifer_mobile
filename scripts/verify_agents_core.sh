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
SRC="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile"
RESILIENCE_DIR="$SRC/resilience"
AGENTS_DIR="$SRC/agents"
TACTICIAN_DIR="$SRC/tactician"
ADAPTER_DIR="$SRC/agents/android"
HARNESS="$ROOT/tools/agents-core/AgentCoreTests.kt"
STUBS="$ROOT/tools/agents-core/android-stubs"
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
CORE_FILES=()
while IFS= read -r file; do
  case "$(basename "$file")" in
    ResilienceEngine.kt) continue ;;
    *) CORE_FILES+=("$file") ;;
  esac
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
echo "== type-check the Android adapters against stubs =="
"$KOTLINC_BIN" "$STUBS"/*.kt "${CORE_FILES[@]}" "$AGENTS_DIR"/*.kt "$TACTICIAN_DIR"/*.kt \
  "$ADAPTER_DIR"/*.kt "$SRC/omni_bridge/heartbeat/HeartbeatService.kt" \
  -d "$OUT/adapters-check" -nowarn
echo "adapter type-check passed (stubs cover Android/JVM surface only; UI and runtime unverified)"
