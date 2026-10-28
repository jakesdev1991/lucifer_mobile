#!/usr/bin/env bash
# Host-side verification for the Lean verification server's control plane.
#
# The phone cannot elaborate Lean, so this checks everything around the elaboration: key handling,
# scopes, quota, source screening, the MCP wire protocol, the exact remote command, result parsing and
# the tunnel command. Those are pure Kotlin; the socket, SSH transport and panel need a device.
#
# Requires: java (17+) and kotlinc, or set KOTLINC/JAVA_HOME.
#
#   ./scripts/verify_verification_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile"
HARNESS="$ROOT/tools/verification-core/VerificationCoreTests.kt"
HARNESS_SERVER="$ROOT/tools/verification-core/VerificationServerTests.kt"
OUT="${TMPDIR:-/tmp}/lucifer-verification-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
if ! command -v "$KOTLINC_BIN" >/dev/null 2>&1; then
  echo "kotlinc not found. Set KOTLINC=/path/to/kotlinc (and JAVA_HOME) to run this harness." >&2
  exit 127
fi

mkdir -p "$OUT"

echo "== compile + run verification control-plane checks =="
"$KOTLINC_BIN" "$SRC/resilience/JsonLite.kt" \
  "$SRC/verification/VerificationCore.kt" "$SRC/verification/VerificationRuntime.kt" \
  "$SRC/verification/VerificationServer.kt" \
  "$HARNESS" "$HARNESS_SERVER" \
  -d "$OUT/verification-tests.jar" -nowarn

echo
echo "== type-check the Android adapters against stubs =="
"$KOTLINC_BIN" "$ROOT/tools/verification-core/android-stubs"/*.kt \
  "$SRC/resilience/JsonLite.kt" \
  "$SRC/verification/VerificationCore.kt" "$SRC/verification/VerificationRuntime.kt" \
  "$SRC/verification/VerificationServer.kt" "$SRC/verification/VmJobExecutor.kt" \
  "$SRC/verification/VerifierController.kt" "$SRC/verification/VerificationService.kt" \
  -d "$OUT/adapters-check" -nowarn
echo "adapter type-check passed (stubs cover the Android surface only; the SSH transport, the socket and the panel are unverified)"

STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
if [[ -f "$STDLIB" ]]; then
  java -cp "$OUT/verification-tests.jar:$STDLIB" com.lucifer.mobile.verification.harness.VerificationCoreTestsKt
  echo
  java -cp "$OUT/verification-tests.jar:$STDLIB" com.lucifer.mobile.verification.server.harness.VerificationServerTestsKt
else
  java -cp "$OUT/verification-tests.jar" com.lucifer.mobile.verification.harness.VerificationCoreTestsKt
  echo
  java -cp "$OUT/verification-tests.jar" com.lucifer.mobile.verification.server.harness.VerificationServerTestsKt
fi
