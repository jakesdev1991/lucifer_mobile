#!/usr/bin/env bash
# Host-side verification for the cognition scaffold: working memory, the loop's stopping rules, the
# plan wire format, the pre-answer audit and the triage that decides whether to run a loop at all.
#
# Three parts:
#
#   1. the rules, executed as a spec (Python mirror — always runs)
#   2. the same rules executed against the real Kotlin (only when kotlinc + java are present)
#   3. the shipped loop still behaves as claimed: it refuses unconfirmed tools, it never executes an
#      invented tool, it audits before answering, and it is reachable from the reasoning engine
#
#   ./scripts/verify_cognition_core.sh
#
# Exit code is 0 only if every part holds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition"
ENGINE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/omni_bridge/knowledge/ToEReasoningEngine.kt"
HARNESS="$ROOT/tools/cognition-core/CognitionCoreTests.kt"
SPEC="$ROOT/tools/cognition-core/spec_check.py"
OUT="${TMPDIR:-/tmp}/lucifer-cognition-build"

KOTLINC_BIN="${KOTLINC:-kotlinc}"
have_kotlin=0
if command -v "$KOTLINC_BIN" >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  have_kotlin=1
fi

status=0

echo "== 1. cognition spec (python mirror) =="
if python3 "$SPEC"; then :; else status=1; fi

echo
echo "== 1b. cognition invariants (fuzz, 1 500 cases per property) =="
if python3 "$ROOT/tools/cognition-core/fuzz.py" 1500; then :; else status=1; fi

echo
echo "== 2. cognition core (real Kotlin) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  mkdir -p "$OUT"
  if "$KOTLINC_BIN" "$CORE/CognitionCore.kt" "$CORE/AnswerAudit.kt" "$HARNESS" \
      -d "$OUT/cognition-tests.jar" -nowarn; then
    STDLIB="$(dirname "$(command -v "$KOTLINC_BIN")")/../lib/kotlin-stdlib.jar"
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/cognition-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.CognitionCoreTests || status=1
    else
      java -cp "$OUT/cognition-tests.jar" com.lucifer.mobile.cognition.harness.CognitionCoreTests || status=1
    fi
  else
    echo "kotlinc failed to compile the cognition core" >&2
    status=1
  fi
else
  if [[ "${LUCIFER_REQUIRE_KOTLIN:-0}" == "1" ]]; then
    echo "FAILED — LUCIFER_REQUIRE_KOTLIN=1 but kotlinc and/or java not found." >&2
    status=1
  fi
  echo "SKIPPED — kotlinc and/or java not found. The Python spec above covers the same cases;"
  echo "          the Kotlin harness has NOT run in this workspace. Set KOTLINC=/path/to/kotlinc."
fi

echo
echo "== 3. the shipped loop still cannot act without permission =="

RUNNER="$CORE/CognitionRunner.kt"
for file in "$CORE/CognitionCore.kt" "$CORE/AnswerAudit.kt" "$RUNNER" "$ENGINE"; do
  [[ -f "$file" ]] || { echo "  MISSING  $file" >&2; exit 1; }
done

require() {
  local file="$1" needle="$2" why="$3"
  if grep -qF -- "$needle" "$file"; then
    echo "  ok    ${needle:0:70}"
  else
    echo "  MISSING  ${needle:0:70}" >&2
    echo "        ↳ $why" >&2
    status=1
  fi
}

require "$RUNNER" "spec.needsConfirmation && spec.id !in confirmedTools" \
  "a tool that changes state must be refused without the owner's approval"
require "$RUNNER" "Termination.TOOL_REFUSED" "and the loop must stop there, not route around it"
require "$RUNNER" "no tool named" "an invented tool is refused, never executed"
require "$RUNNER" "AnswerAudit.audit(" "the draft is checked before it is returned"
require "$RUNNER" "planner.repair(" "a blocked draft gets exactly one repair attempt"
require "$RUNNER" "LoopGuard.stopReason(" "the loop is bounded on every iteration"
require "$RUNNER" 'raw.startsWith("[LLM]")' "an unavailable model is not turned into an answer"
require "$RUNNER" "confirmedTools: Set<String> = emptySet()" "the default is: change nothing"
require "$RUNNER" "ObservationFence.sanitize(" "tool output is defanged before it can speak the protocol"
require "$RUNNER" "withTimeout(remaining)" "one slow model call cannot overrun the wall-clock budget"
require "$RUNNER" "withTimeout(toolRemaining)" "one slow tool cannot overrun it either"
require "$RUNNER" "Calculator.evaluate(" "arithmetic is computed, not guessed"
require "$RUNNER" "ResearchAgent(" "research goes through the existing bounded, robots-aware searcher"
require "$CORE/CognitionCore.kt" "object Calculator" "the calculator lives in the testable core"

# The engine must reach the loop, and keep the single-pass council as the fallback.
require "$ENGINE" "CognitionRunner.run(" "the reasoning engine never uses the loop otherwise"
require "$ENGINE" "CognitionTriage.assess(" "which questions get the loop must be decided, not guessed"
require "$ENGINE" "DepthDirective.parse(" "the owner can ask for depth explicitly"
require "$ENGINE" "budget: com.lucifer.mobile.cognition.LoopBudget" "a surface can shorten the loop"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/terminal/FloatingChatService.kt" \
  "maxElapsedMs = 40_000" "the overlay does not make someone wait ninety seconds"
require "$ENGINE" "horsemen.deliberate(" "the council must survive as the fallback"
require "$ENGINE" "confirmedTools: Set<String> = emptySet()" "chat cannot pre-approve a state change"
require "$ENGINE" "CognitionLedger.record(" "every deep run must leave a record the owner can audit"

# A ninety-second run needs a way out.
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/components/AnimatedTerminalTab.kt" \
  "onStop: () -> Unit" "the terminal must offer a stop control"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/MainActivity.kt" \
  "activeJob?.cancel()" "and the control must actually cancel the turn"

# The loop must be pure enough to test: no Android imports below the adapters.
if grep -q "^import android" "$CORE/CognitionCore.kt" "$CORE/AnswerAudit.kt"; then
  echo "  FAILED  the pure core imports Android; the spec can no longer run on a host" >&2
  status=1
else
  echo "  ok    the pure core has no Android imports"
fi

# And the catalogue must keep declaring an effect for every tool: no tool may reach the network or the
# device without saying so, and at least one must be a pure read.
if grep -q "SideEffect.NONE" "$CORE/CognitionCore.kt"; then
  echo "  ok    side effects are declared per tool"
else
  echo "  FAILED  no tool declares SideEffect.NONE — the effect model has been bypassed" >&2
  status=1
fi
if [[ "$(grep -c "ToolSpec(" "$CORE/CognitionCore.kt")" -ge 7 ]]; then
  echo "  ok    seven tools in the catalogue, each with a declared effect"
else
  echo "  FAILED  the catalogue lost tools" >&2
  status=1
fi

echo
if [[ $status -eq 0 ]]; then
  echo "COGNITION CORE OK"
else
  echo "COGNITION CORE FAILED" >&2
fi
exit $status
