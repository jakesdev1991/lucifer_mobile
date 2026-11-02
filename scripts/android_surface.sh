#!/usr/bin/env bash
# Where the Android surface for an adapter type-check comes from.
#
# The `verify_*_core.sh` harnesses compile Lucifer's Android adapters on a host with no device and no
# Gradle. The `android.*` classes those adapters call have to come from somewhere, and there are two
# honest sources:
#
#   1. the real SDK: `platforms/android-<compileSdk>/android.jar` from LUCIFER_ANDROID_JAR, ANDROID_JAR,
#      ANDROID_HOME, ANDROID_SDK_ROOT or `sdk.dir` in local.properties. Compiling against it proves the
#      adapter calls an API the SDK actually has.
#   2. `tools/*-core/android-stubs`, hand-written classes that model the surface Lucifer uses.
#
# Those stubs are a *claim* about Android, and a claim can rot: `verify_privilege_core.sh` failed in CI
# because its stubs put `CrossProfileApps` in `android.content` instead of `android.content.pm`, gave
# `logoutUser` a second parameter the platform does not have, and invented `UserManager.getUserInfo`.
# The adapters were correct the whole time; the check was validating the stubs against themselves.
#
# So: prefer the real jar whenever one can be found, fall back to the stubs, and say in the output
# which one was used — a green run against stubs is a weaker result than a green run against the SDK,
# and the log should never blur the two. CI has ANDROID_HOME with the platforms installed, so CI checks
# the real surface. `LUCIFER_REQUIRE_ANDROID_JAR=1` turns a missing jar into a failure instead of a
# quiet downgrade.
#
# Source this file, then:
#   android_surface_guard                                                 # fail early if a jar was demanded
#   mapfile -t SURFACE < <(android_surface_stub_files "$STUBS")            # the stub files to keep
#   mapfile -t JAR_ARGS < <(android_surface_classpath)                     # -classpath <jar>, or nothing
#   android_surface_report "$STUBS"                                        # the line naming what was checked
#
# Reads files only; installs nothing.

LUCIFER_ROOT="${LUCIFER_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

android_compile_sdk() {
  local gradle="$LUCIFER_ROOT/composeApp/build.gradle.kts"
  local api=""
  if [[ -f "$gradle" ]]; then
    api="$(sed -n 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$gradle" | head -1)"
  fi
  echo "${api:-35}"
}

# The first android.jar this host can offer, or nothing.
android_surface_jar() {
  local api candidate sdk
  api="$(android_compile_sdk)"

  for candidate in "${LUCIFER_ANDROID_JAR:-}" "${ANDROID_JAR:-}"; do
    [[ -n "$candidate" && -f "$candidate" ]] && { echo "$candidate"; return 0; }
  done

  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [[ -z "$sdk" ]] && continue
    candidate="$sdk/platforms/android-$api/android.jar"
    [[ -f "$candidate" ]] && { echo "$candidate"; return 0; }
  done

  if [[ -f "$LUCIFER_ROOT/local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir[[:space:]]*=[[:space:]]*//p' "$LUCIFER_ROOT/local.properties" | head -1)"
    if [[ -n "$sdk" ]]; then
      candidate="$sdk/platforms/android-$api/android.jar"
      [[ -f "$candidate" ]] && { echo "$candidate"; return 0; }
    fi
  fi

  return 1
}

# `-classpath <jar>` when a real jar exists, nothing when it does not. One argument per line so callers
# can `mapfile` it without word splitting.
android_surface_classpath() {
  local jar
  if jar="$(android_surface_jar)"; then
    printf -- '-classpath\n%s\n' "$jar"
  fi
}

# The stub files to compile. With a real jar, every `android.*` stub is dropped — the jar replaces it,
# and keeping both would only shadow the SDK and hide the drift this exists to prevent. Every other stub
# (kotlinx, org.json, androidx, Lucifer's own ports) is kept either way: those model things that are not
# in the jar.
android_surface_stub_files() {
  local dir="$1" file keep_android=1
  if android_surface_jar >/dev/null; then
    keep_android=0
  fi
  for file in "$dir"/*.kt; do
    [[ -e "$file" ]] || continue
    if [[ $keep_android -eq 0 ]] && grep -qE '^package android\.' "$file"; then
      continue
    fi
    echo "$file"
  done
}

# Every stub file, including the `android.*` ones. Use this when the Android stubs are *fixtures* —
# objects the harness subclasses or instantiates to exercise Lucifer's code — rather than a claim about
# the SDK. `android.content.Context` is abstract in the real jar, so a harness that subclasses it can
# never run against the jar; it needs its own concrete stand-in. Those harnesses should ALSO compile
# their app sources against the jar (android_surface_stub_files + android_surface_classpath) so the
# adapters still get a real check.
android_surface_fixture_files() {
  local dir="$1"
  printf '%s\n' "$dir"/*.kt
}

# One line in the run log naming the surface that was actually checked.
android_surface_report() {
  local dir="$1" jar count=0 f
  if jar="$(android_surface_jar)"; then
    echo "surface: real android.jar ($jar)"
    return 0
  fi
  for f in "$dir"/*.kt; do
    [[ -e "$f" ]] && grep -qE '^package android\.' "$f" && count=$((count + 1))
  done
  echo "surface: $count hand-written Android stub file(s) — no android.jar found, so this pass proves the"
  echo "         adapters agree with the stubs, not with the SDK. Fix: ANDROID_HOME or LUCIFER_ANDROID_JAR."
}

# Call before compiling. Anyone who set LUCIFER_REQUIRE_ANDROID_JAR=1 wants the real surface; falling
# back to stubs after that would be a silent downgrade of exactly the claim the run is making.
android_surface_guard() {
  [[ "${LUCIFER_REQUIRE_ANDROID_JAR:-0}" == "1" ]] || return 0
  if ! android_surface_jar >/dev/null; then
    echo "LUCIFER_REQUIRE_ANDROID_JAR=1 but no android.jar (API $(android_compile_sdk)) was found." >&2
    echo "Point ANDROID_HOME at an SDK with platforms;android-$(android_compile_sdk), or set LUCIFER_ANDROID_JAR." >&2
    exit 1
  fi
}
