#!/usr/bin/env bash
# Host-side verification for the cognition scaffold: working memory, the loop's stopping rules, the
# plan wire format, the pre-answer audit and the triage that decides whether to run a loop at all.
#
# Three parts:
#
#   1. the rules, executed as a spec (Python mirror — always runs)
#   2. the same rules executed against the real Kotlin (only when kotlinc + java are present)
#   1c. the loop end to end, driven by scripted replies (tools/cognition-core/loop_sim.py)
#   3. the shipped loop still behaves as claimed: it refuses unconfirmed tools, it never executes an
#      invented tool, it audits before answering, and it is reachable from the reasoning engine
#
#   ./scripts/verify_cognition_core.sh
#
# Exit code is 0 only if every part holds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/android_surface.sh
source "$ROOT/scripts/android_surface.sh"
android_surface_guard
CORE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/cognition"
ENGINE="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/omni_bridge/knowledge/ToEReasoningEngine.kt"
HARNESS="$ROOT/tools/cognition-core/CognitionCoreTests.kt"
RUNNER_HARNESS="$ROOT/tools/cognition-core/RunnerTests.kt"
LEDGER_HARNESS="$ROOT/tools/cognition-core/LedgerTests.kt"
SCORES_HARNESS="$ROOT/tools/cognition-core/ScoresTests.kt"
TOOLS_HARNESS="$ROOT/tools/cognition-core/ToolsTests.kt"
PLANNER_HARNESS="$ROOT/tools/cognition-core/PlannerTests.kt"
FUZZ_RUNNER="$ROOT/tools/cognition-core/RunnerFuzz.kt"
GATE_HARNESS="$ROOT/tools/cognition-core/OutputGateTests.kt"
STUBS="$ROOT/tools/cognition-core/android-stubs"
# Two different uses of the same directory, and they must not be confused:
#   FIXTURES — every stub, because the runnable harnesses subclass `android.content.Context` and give it
#              a temp dir. The real jar's Context is abstract, so it cannot stand in here.
#   SURFACE  — the non-Android stubs plus the real android.jar, for the adapter compile at the end.
mapfile -t FIXTURES < <(android_surface_fixture_files "$STUBS")
mapfile -t SURFACE < <(android_surface_stub_files "$STUBS")
mapfile -t JAR_ARGS < <(android_surface_classpath)
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
echo "== 1c. the loop, end to end (scripted model, simulated tools) =="
if python3 "$ROOT/tools/cognition-core/loop_sim.py" 400; then :; else status=1; fi

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
echo "== 2b. the loop itself, executed (scripted model, recording toolbox) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  # The whole package: the loop, the ledger and the tally reference each other, and every one of them
  # is real code compiled from source.
  if "$KOTLINC_BIN" "${FIXTURES[@]}" "$CORE"/*.kt "$RUNNER_HARNESS" -d "$OUT/runner-tests.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/runner-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.RunnerTestsKt || status=1
    else
      java -cp "$OUT/runner-tests.jar" com.lucifer.mobile.cognition.harness.RunnerTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the runner harness" >&2
    status=1
  fi
else
  echo "SKIPPED — kotlinc and/or java not found, so CognitionRunner.run has NOT been executed."
fi

echo
echo "== 2c. the ledger a run leaves behind =="
if [[ "$have_kotlin" -eq 1 ]]; then
  if "$KOTLINC_BIN" "${FIXTURES[@]}" "$CORE"/*.kt "$LEDGER_HARNESS" -d "$OUT/ledger-tests.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/ledger-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.LedgerTestsKt || status=1
    else
      java -cp "$OUT/ledger-tests.jar" com.lucifer.mobile.cognition.harness.LedgerTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the ledger harness" >&2
    status=1
  fi
else
  echo "SKIPPED — kotlinc and/or java not found, so CognitionLedger has NOT been executed."
fi

echo
echo "== 2d. the tool tally the loop learns from =="
if [[ "$have_kotlin" -eq 1 ]]; then
  if "$KOTLINC_BIN" "${FIXTURES[@]}" "$CORE"/*.kt "$SCORES_HARNESS" -d "$OUT/scores-tests.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/scores-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.ScoresTestsKt || status=1
    else
      java -cp "$OUT/scores-tests.jar" com.lucifer.mobile.cognition.harness.ScoresTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the tool-tally harness" >&2
    status=1
  fi
else
  echo "SKIPPED — kotlinc and/or java not found, so CognitionScores has NOT been executed."
fi

echo
echo "== 2e. the six tools the app actually ships =="
if [[ "$have_kotlin" -eq 1 ]]; then
  if "$KOTLINC_BIN" "${FIXTURES[@]}" "$CORE"/*.kt "$TOOLS_HARNESS" -d "$OUT/tools-tests.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/tools-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.ToolsTestsKt || status=1
    else
      java -cp "$OUT/tools-tests.jar" com.lucifer.mobile.cognition.harness.ToolsTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the tool harness" >&2
    status=1
  fi
else
  echo "SKIPPED — kotlinc and/or java not found, so AndroidCognitionTools has NOT been executed."
fi

echo
echo "== 2f. the prompt the model actually reads =="
if [[ "$have_kotlin" -eq 1 ]]; then
  if "$KOTLINC_BIN" "${FIXTURES[@]}" "$CORE"/*.kt "$PLANNER_HARNESS" -d "$OUT/planner-tests.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/planner-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.PlannerTestsKt || status=1
    else
      java -cp "$OUT/planner-tests.jar" com.lucifer.mobile.cognition.harness.PlannerTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the planner harness" >&2
    status=1
  fi
else
  echo "SKIPPED — kotlinc and/or java not found, so NemotronCognitionPlanner has NOT been executed."
fi

echo
echo "== 2g. the loop under a hostile model (property test) =="
if [[ "$have_kotlin" -eq 1 ]]; then
  if "$KOTLINC_BIN" "${FIXTURES[@]}" "$CORE"/*.kt "$FUZZ_RUNNER" -d "$OUT/runner-fuzz.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/runner-fuzz.jar:$STDLIB" com.lucifer.mobile.cognition.harness.RunnerFuzzKt \
        "${RUNNER_FUZZ_RUNS:-200}" "${RUNNER_FUZZ_SEED:-20261030}" || status=1
    else
      java -cp "$OUT/runner-fuzz.jar" com.lucifer.mobile.cognition.harness.RunnerFuzzKt \
        "${RUNNER_FUZZ_RUNS:-200}" "${RUNNER_FUZZ_SEED:-20261030}" || status=1
    fi
  else
    echo "kotlinc failed to compile the runner fuzz" >&2
    status=1
  fi
else
  echo "SKIPPED — kotlinc and/or java not found, so the loop has NOT been fuzzed."
fi

echo
echo "== 2h. every reply passes the output gate =="
# The gate is the one thing every answer meets before a person reads it, including the single-pass
# council path that used to ship whatever the model wrote. It is pure Kotlin, so unlike the Android
# adapters it needs no SDK and no fixtures — it is simply executed.
if [[ "$have_kotlin" -eq 1 ]]; then
  if "$KOTLINC_BIN" "$CORE/OutputGate.kt" "$CORE/AnswerAudit.kt" "$CORE/CognitionCore.kt" \
      "$GATE_HARNESS" -d "$OUT/gate-tests.jar" -nowarn; then
    if [[ -f "$STDLIB" ]]; then
      java -cp "$OUT/gate-tests.jar:$STDLIB" com.lucifer.mobile.cognition.harness.OutputGateTestsKt || status=1
    else
      java -cp "$OUT/gate-tests.jar" com.lucifer.mobile.cognition.harness.OutputGateTestsKt || status=1
    fi
  else
    echo "kotlinc failed to compile the output-gate harness" >&2
    status=1
  fi
else
  echo "  SKIPPED — kotlinc not found, so the gate has NOT been executed."
fi

echo
echo "== 3. the shipped loop still cannot act without permission =="

RUNNER="$CORE/CognitionRunner.kt"
LEDGER="$CORE/CognitionLedger.kt"
for file in "$CORE/CognitionCore.kt" "$CORE/AnswerAudit.kt" "$RUNNER" "$LEDGER" "$ENGINE" \
    "$CORE/CognitionScores.kt"; do
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
require "$ENGINE" "rememberTurn(input, shown)" \
  "every *shown* turn is remembered, not only the deep ones — a follow-up after a shallow answer had no context, and a withheld one is not context"
require "$ENGINE" "while (recentTurns.size > 2)" "the window is the two turns the self-description promises"
require "$RUNNER" "withTimeout(remaining)" "one slow model call cannot overrun the wall-clock budget"
require "$RUNNER" "withTimeout(toolRemaining)" "one slow tool cannot overrun it either"
require "$RUNNER" "Calculator.evaluate(" "arithmetic is computed, not guessed"
require "$RUNNER" "ResearchAgent(" "research goes through the existing bounded, robots-aware searcher"
require "$RUNNER" "parsed.answer ?: raw.trim().takeIf { parsed.unstructured" \
  "a THOUGHT with no ANSWER must never be shipped as the reply"
require "$RUNNER" "thoughtsOnly >= 3" "and three in a row stops the loop instead of burning the budget"
require "$RUNNER" "call a tool, or answer" "with one plain correction first"
require "$CORE/CognitionCore.kt" "object Calculator" "the calculator lives in the testable core"

# The funnel itself, pinned. `ToEReasoningEngine` cannot be type-checked on a host without rebuilding
# the app's whole dependency graph out of stubs, so its shape is asserted here instead: three routes in,
# one verdict out, and no fourth way to return a bare string.
require "$ENGINE" "private suspend fun gateDraft(" \
  "producing a reply and gating it must be one private step, so no caller can reach an ungated draft"
require "$ENGINE" "val verdict = gateDraft(input, surface, onStep, budget)" \
  "reason() asks the gate, and nothing else"
require "$ENGINE" "val shown = verdict.text ?: OutputGate.refusal(verdict)" \
  "only a verdict with text is shown; a blocked draft becomes a refusal"
require "$ENGINE" "return OutputGate.deterministic(" \
  "the deterministic surface answer is declared as such rather than audited as model prose"
require "$ENGINE" "return OutputGate.carried(" \
  "the loop's already-gated answer carries its verdict to the boundary"
require "$ENGINE" "return OutputGate.gate(" \
  "the single-pass council answer is audited at the boundary — this path shipped unchecked before"
require "$ENGINE" "if (!verdict.blocked) rememberTurn(input, shown)" \
  "a refusal is not remembered as an answer Lucifer established"
# The heartbeat is the only other route that writes model prose a person reads, and it calls the model
# directly. It is gated by the same object rather than by a copy of the rules.
HEARTBEAT="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/omni_bridge/heartbeat/HeartbeatService.kt"
require "$HEARTBEAT" "OutputGate.gate(" \
  "a self-initiated heartbeat draft passes the gate before it is filed for the owner to read"
require "$HEARTBEAT" "val deliverable = gate.text ?: com.lucifer.mobile.cognition.OutputGate.refusal(gate)" \
  "a blocked heartbeat draft is withheld, and the refusal takes its place in the file"
require "$HEARTBEAT" 'put("gateBlocked", gate.blocked)' \
  "the verdict is recorded, so a withheld draft is auditable instead of invisible"

if grep -qE "^[[:space:]]*return (answer|buildString)" "$ENGINE"; then
  echo "  FAILED  a bare return in ToEReasoningEngine bypasses the gate" >&2
  grep -nE "^[[:space:]]*return (answer|buildString)" "$ENGINE" | sed 's/^/        /' >&2
  status=1
else
  echo "  ok    no bare return of an ungated string survives in the engine"
fi

echo
echo "== 3b. the loop learns which tools actually work here =="
SCORES="$CORE/CognitionScores.kt"
require "$CORE/CognitionCore.kt" "object ToolRecord" "the tally is pure, and its thresholds are testable"
require "$CORE/CognitionCore.kt" "const val MIN_CALLS = 3" "two bad calls is not evidence, and must not be treated as one"
require "$SCORES" "record.call ?: return@mapNotNull null" "only calls that really happened are counted"
require "$SCORES" "result.ok && result.output.isNotBlank()" "an empty result is a failed call, not a success"
require "$RUNNER" "CognitionScores.adviceLine(context)" "the planner reads the record on every step"
require "$ENGINE" "CognitionScores.record(appContext, outcome.trace)" "and the record is written from the trace after the run"
SETTINGS="$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/components/SettingsPanel.kt"
require "$SETTINGS" "onClearCognition: () -> Unit" "the owner can erase the thinking history, like the egress log"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/MainActivity.kt" \
  "CognitionLedger.clear(this@MainActivity)" "and the control actually deletes it"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/data/export/ExportBundle.kt" \
  "cognitionTools" "the export carries the tool counts (never their output)"
if [[ -f "$SCORES" ]]; then
  if grep -q "output" "$SCORES" && grep -qE "put\(\"(output|body|text)\"" "$SCORES"; then
    echo "  MISSING  the score file must not persist tool output" >&2
    status=1
  else
    echo "  ok    the score file holds counts, never tool output"
  fi
fi

# The engine must reach the loop, and keep the single-pass council as the fallback.
require "$ENGINE" "CognitionRunner.run(" "the reasoning engine never uses the loop otherwise"
require "$ENGINE" "CognitionTriage.assess(" "which questions get the loop must be decided, not guessed"
require "$ENGINE" "DepthDirective.parse(" "the owner can ask for depth explicitly"
require "$ENGINE" "budget: com.lucifer.mobile.cognition.LoopBudget" "a surface can shorten the loop"
require "$CORE/CognitionCore.kt" "val OVERLAY = LoopBudget(maxSteps = 4, maxElapsedMs = 40_000" \
  "the overlay does not make someone wait ninety seconds"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/terminal/FloatingChatService.kt" \
  "LoopBudget.OVERLAY" "and it uses that budget instead of a second copy of the numbers"
# Every surface listed as short-budget in forSurface must really pass it, or the description lies.
for surface_file in \
  "composeApp/src/androidMain/kotlin/com/lucifer/mobile/launcher/LauncherActivity.kt" \
  "composeApp/src/androidMain/kotlin/com/lucifer/mobile/terminal/FloatingChatService.kt"; do
  require "$ROOT/$surface_file" "LoopBudget.OVERLAY" \
    "$(basename "$surface_file") runs on the budget it advertises"
done
require "$ENGINE" "horsemen.deliberate(" "the council must survive as the fallback"
require "$ENGINE" "confirmedTools: Set<String> = emptySet()" "chat cannot pre-approve a state change"
require "$ENGINE" "CognitionLedger.record(" "every deep run must leave a record the owner can audit"

# A forty-second overlay run must say what it is doing, or it reads as a hang.
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/launcher/ChatDialog.kt" \
  "progress: String = \"\"" "the launcher dialog can show the current step"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/launcher/LauncherActivity.kt" \
  "onStep = { record ->" "the launcher passes the loop's steps to it"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/terminal/FloatingChatService.kt" \
  "onStep = { record ->" "and so does the floating overlay"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/terminal/FloatingChatService.kt" \
  "scope.launch { uiState = uiState.copy(progress = line) }" \
  "written on the main thread, not from the worker that reported it"

# A ninety-second run needs a way out.
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/components/AnimatedTerminalTab.kt" \
  "onStop: () -> Unit" "the terminal must offer a stop control"
require "$ROOT/composeApp/src/androidMain/kotlin/com/lucifer/mobile/MainActivity.kt" \
  "activeJob?.cancel()" "and the control must actually cancel the turn"
# Swallowing a cancellation turns a person's decision into a "failed observation".
if grep -q 'catch (stop: CancellationException)' "$RUNNER"; then
  echo "  ok    a stop unwinds instead of becoming a failed observation"
else
  echo "  MISSING  the loop swallows CancellationException" >&2
  echo "        ↳ a person pressing STOP would be recorded as a tool failure" >&2
  status=1
fi
require "$LEDGER" "recordInterrupted" "an interrupted run still reaches the history"

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
echo "== 5. the cognition adapters compile against the Android surface =="
# The runnable parts above use fixtures (the harness subclasses Context). This part is the other check:
# Lucifer's own cognition sources, compiled with no fixtures at all, against the real android.jar when
# there is one. A call the platform does not have fails here rather than in the APK build nobody runs.
if [[ "$have_kotlin" -eq 1 ]]; then
  android_surface_report "$STUBS"
  if "$KOTLINC_BIN" ${JAR_ARGS[@]+"${JAR_ARGS[@]}"} "${SURFACE[@]}" "$CORE"/*.kt \
      -d "$OUT/adapter-check" -nowarn; then
    echo "  ok    the cognition adapters type-check"
  else
    echo "  FAILED  the cognition adapters do not type-check" >&2
    status=1
  fi
else
  echo "  SKIPPED — kotlinc not found, so the adapters were not type-checked."
fi

echo
if [[ $status -eq 0 ]]; then
  echo "COGNITION CORE OK"
else
  echo "COGNITION CORE FAILED" >&2
fi
exit $status
