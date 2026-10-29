#!/usr/bin/env bash
# Run every host-side verification in the repo, once, with one exit code.
#
# There are eight `verify_*_core.sh` harnesses and, until now, no way to run them together and no CI
# that ran them at all. A check nobody runs is a comment. This is the entry point CI uses and the one a
# person should run before pushing:
#
#   ./scripts/verify_all.sh            # every harness
#   ./scripts/verify_all.sh --quiet    # only the summary
#
# It keeps going after a failure so one broken harness does not hide the state of the other six, and it
# reports SKIPPED separately from PASS: a harness whose Kotlin step could not compile is not a pass, it
# is an unproven claim, and that difference is the whole point of these scripts.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

QUIET=0
[[ "${1:-}" == "--quiet" ]] && QUIET=1

mapfile -t HARNESSES < <(find "$ROOT/scripts" -maxdepth 1 -name 'verify_*_core.sh' -type f | sort)
mapfile -t TOOLS < <(find "$ROOT/tools" -name 'audit_*.py' -type f | sort)

passed=0
failed=0
skipped=0
failed_names=()

run() {
  local label="$1"
  shift
  local output status
  output="$("$@" 2>&1)"
  status=$?
  local did_skip=0
  grep -q 'SKIPPED' <<<"$output" && did_skip=1

  if [[ $status -ne 0 ]]; then
    failed=$((failed + 1))
    failed_names+=("$label")
    echo "FAIL   $label"
    echo "$output" | sed 's/^/       /'
  elif [[ $did_skip -eq 1 ]]; then
    skipped=$((skipped + 1))
    echo "SKIP   $label (part of it could not run here)"
    [[ $QUIET -eq 0 ]] && echo "$output" | sed 's/^/       /'
  else
    passed=$((passed + 1))
    echo "PASS   $label"
    [[ $QUIET -eq 0 ]] && echo "$output" | sed 's/^/       /'
  fi
  return 0
}

echo "== harnesses =="
for harness in "${HARNESSES[@]}"; do
  [[ -x "$harness" ]] || chmod +x "$harness" 2>/dev/null || true
  run "$(basename "$harness")" bash "$harness"
done

echo
echo "== static audits =="
for tool in "${TOOLS[@]}"; do
  run "$(basename "$tool")" python3 "$tool" --check
done

# The config audit lives under tools/config-core with the others; make sure it is not silently absent.
if ! printf '%s\n' "${TOOLS[@]}" | grep -q 'audit_config.py'; then
  run "audit_config.py" python3 "$ROOT/tools/config-core/audit_config.py" --check
fi

echo
echo "-------------------------------------------"
echo "PASS $passed · SKIP $skipped · FAIL $failed"
if [[ $failed -ne 0 ]]; then
  echo "failed: ${failed_names[*]}"
  echo "VERIFY FAILED"
  exit 1
fi
if [[ $skipped -ne 0 ]]; then
  # Not a failure: the Kotlin half of these harnesses needs a JDK that this host may not have.
  echo "One or more harnesses skipped their Kotlin step — that half is unproven here, not passing."
fi
echo "VERIFY OK"
