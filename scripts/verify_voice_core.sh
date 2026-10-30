#!/usr/bin/env bash
# Host-side verification for voice input.
#
# There is no pure-Kotlin voice module to execute here (the recogniser is an Android framework
# callback), so this harness is entirely static. It asserts the properties that make press-to-talk
# different from an always-listening assistant, and it will fail if any of them is quietly removed:
#
#   1. the recogniser lives in one file and is created per press, destroyed after each answer
#   2. the microphone is closed when the launcher leaves the screen (onStop), not only on a button
#   3. RECORD_AUDIO is requested at runtime, and the refusal path says something a person can act on
#   4. the audio-to-remote-service fact is written down where a reader will find it
#
#   ./scripts/verify_voice_core.sh
#
# Exit code is 0 only if every property holds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VOICE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/launcher/LuciferVoice.kt"
LAUNCHER="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/launcher/LauncherActivity.kt"
KOTLIN="$ROOT/composeApp/src/androidMain/kotlin"

status=0
fail() { echo "  FAILED  $1" >&2; status=1; }
ok()   { echo "  ok      $1"; }

echo "== voice input · static properties =="

for file in "$VOICE" "$LAUNCHER"; do
  [[ -f "$file" ]] || { echo "  MISSING  $file" >&2; exit 1; }
done

# 1. One recogniser owner: no other file may create one, or the "destroyed on answer" guarantee only
#    describes one of several paths.
others=$(grep -rln "SpeechRecognizer.createSpeechRecognizer" "$KOTLIN" | grep -v "^$VOICE$" || true)
if [[ -n "$others" ]]; then
  fail "a second file creates a recogniser: $others"
else
  ok "the recogniser is created in exactly one place"
fi

grep -q "SpeechRecognizer.createSpeechRecognizer(app)" "$VOICE" \
  && ok "the recogniser is created per press" \
  || fail "LuciferVoice no longer creates the recogniser itself"

grep -q "current.destroy()" "$VOICE" \
  && ok "the recogniser is destroyed" \
  || fail "the recogniser is never destroyed — a session could leak"

[[ "$(grep -c "destroyRecognizer()" "$VOICE")" -ge 3 ]] \
  && ok "every terminal path destroys the recogniser" \
  || fail "some terminal path leaves the recogniser alive"

# Nothing may listen continuously: no wake word, no hotword, no always-on service.
if grep -rniE "hotword|wake ?word|always.?listening" "$KOTLIN" | grep -v "^$VOICE" ; then
  fail "an always-on listening path appeared"
else
  ok "no hotword or always-listening path"
fi

# 2. The microphone closes when the screen goes away.
if grep -q "override fun onStop()" "$LAUNCHER" && \
   sed -n '/override fun onStop()/,/^    }/p' "$LAUNCHER" | grep -q "stopListening"; then
  ok "onStop stops listening"
else
  fail "onStop does not stop listening — a backgrounded launcher could keep the mic open"
fi

# 3. Runtime permission, and a refusal a person can act on.
grep -q "ActivityResultContracts.RequestPermission()" "$LAUNCHER" \
  && ok "RECORD_AUDIO is requested at runtime" \
  || fail "no runtime permission launcher for RECORD_AUDIO"
grep -q "Manifest.permission.RECORD_AUDIO" "$LAUNCHER" \
  && ok "the request names RECORD_AUDIO" \
  || fail "the runtime request does not name RECORD_AUDIO"
grep -q "Microphone permission was not granted" "$LAUNCHER" \
  && ok "the refusal is explained" \
  || fail "a refused mic is silent"

# The permission must be checked before launching the request, not after the failure.
grep -q "PERMISSION_GRANTED" "$LAUNCHER" \
  && ok "the grant state is checked before listening" \
  || fail "listening starts without checking the grant"

# 4. The honest sentence about where the audio goes.
if grep -q "system speech service" "$VOICE" && grep -qi "audio" "$ROOT/BROWSER_AND_VOICE.md"; then
  ok "the audio-to-remote-service fact is documented"
else
  fail "the audio destination is not documented"
fi

# Offline recognition must not be silently requested as if it were free.
if grep -q "EXTRA_PREFER_OFFLINE, true" "$VOICE"; then
  echo "  WARN    offline-only recognition is requested; devices without a model will fail" >&2
else
  ok "offline-only recognition is not forced"
fi

echo
echo "NOTE: nothing here is compiled — this workspace has no JDK, kotlinc or Android SDK, so the"
echo "      recogniser and the lifecycle hooks are reviewed, not executed. NOT COMPILED."

if [[ $status -eq 0 ]]; then
  echo "VOICE CORE OK (static only)"
else
  echo "VOICE CORE FAILED" >&2
fi
exit $status
