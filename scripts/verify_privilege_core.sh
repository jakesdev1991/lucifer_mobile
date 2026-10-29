#!/usr/bin/env bash
# Host-side verification for the privileged layer and Lucifer's world.
#
# The Android adapters (Dhizuku Binder, Shizuku shell, cross-profile launches) need a device, but the
# decisions are pure Kotlin: which backend holds which capability, what exact command an op becomes,
# what is refused before any command exists, how `pm list users` is parsed, and what the world charter
# allows. This compiles those with an assertion harness and runs them.
#
# Requires: java (17+) and kotlinc, or set KOTLINC/JAVA_HOME.
#
#   ./scripts/verify_privilege_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile"
HARNESS="$ROOT/tools/privilege-core/PrivilegeCoreTests.kt"
OUT="${TMPDIR:-/tmp}/lucifer-privilege-build"

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

echo "== compile + run privilege / world checks =="
"$KOTLINC_BIN" "$SRC/privilege/PrivilegeCore.kt" "$SRC/world/WorldCore.kt" "$HARNESS" \
  -d "$OUT/privilege-tests.jar" -nowarn

echo
echo "== type-check the Android adapters against stubs =="
"$KOTLINC_BIN" "$ROOT/tools/privilege-core/android-stubs"/*.kt \
  "$SRC/privilege/PrivilegeCore.kt" "$SRC/privilege/DhizukuBridge.kt" \
  "$SRC/privilege/LocalOwnerOps.kt" "$SRC/privilege/PrivilegeRouter.kt" \
  "$SRC/world/WorldCore.kt" "$SRC/world/WorldManager.kt" \
  -d "$OUT/adapters-check" -nowarn
echo "adapter type-check passed (stubs cover the Android surface only; the Binder calls and the panel are unverified)"

STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
if [[ -f "$STDLIB" ]]; then
  java -cp "$OUT/privilege-tests.jar:$STDLIB" com.lucifer.mobile.privilege.harness.PrivilegeCoreTestsKt
else
  java -cp "$OUT/privilege-tests.jar" com.lucifer.mobile.privilege.harness.PrivilegeCoreTestsKt
fi
