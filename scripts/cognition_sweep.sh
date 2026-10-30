#!/usr/bin/env bash
# The sweep: everything that can be checked without a device, run in a rotating order, on repeat.
#
# This exists because the interesting failures are not the ones a single run finds. A parser that only
# breaks on one whitespace shape, a memory that only overflows after a hundred appends, a prompt format
# that only misreads after the hundredth model variation — those surface under repetition, with a new
# fuzz seed every pass, not under a single green run.
#
#   ./scripts/cognition_sweep.sh                  # one pass, everything
#   ./scripts/cognition_sweep.sh --loop 20 --interval 60
#   ./scripts/cognition_sweep.sh --loop 0         # forever (Ctrl-C to stop)
#
# Every pass appends one line to the report (default build/sweep/report.log, override with --report),
# so a long unattended run leaves something a person can read afterwards.
#
# For a real cron job on a machine with this checkout (not this sandbox — there is no JDK here):
#
#   */30 * * * * cd /path/to/lucifer_mobile && ./scripts/cognition_sweep.sh --quiet >> /tmp/lucifer-sweep.log 2>&1
#
# Exit code: 0 if every pass in the run passed, 1 if any did not.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOOPS=1
INTERVAL=0
QUIET=0
REPORT="$ROOT/build/sweep/report.log"
FUZZ_CASES=4000

while [[ $# -gt 0 ]]; do
  case "$1" in
    --loop) LOOPS="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --report) REPORT="$2"; shift 2 ;;
    --cases) FUZZ_CASES="$2"; shift 2 ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$(dirname "$REPORT")"
overall=0
pass=0

say() { [[ $QUIET -eq 1 ]] || echo "$@"; }

# A seed that changes every pass: the point is to look somewhere new each time, not to replay.
seed_for() { echo $(( ( $(date +%s) + $1 * 7919 ) % 100000 )); }

run_pass() {
  local index="$1"
  local seed started failures=0
  seed="$(seed_for "$index")"
  started="$(date +%Y-%m-%dT%H:%M:%S)"

  say "── pass $index · seed $seed · $(date +%H:%M:%S)"

  # 1. the spec: every case anyone thought of
  if python3 tools/cognition-core/spec_check.py >/tmp/sweep-spec.txt 2>&1; then
    say "   spec      PASS  $(grep -o 'PASS · [0-9]* checks' /tmp/sweep-spec.txt | head -1)"
  else
    say "   spec      FAIL"; grep -E '^FAIL|FAILED' /tmp/sweep-spec.txt | head -5 | sed 's/^/             /'
    failures=$((failures + 1))
  fi

  # 2. the invariants: cases nobody thought of, a new seed every pass
  if FUZZ_SEED="$seed" python3 tools/cognition-core/fuzz.py "$FUZZ_CASES" >/tmp/sweep-fuzz.txt 2>&1; then
    say "   fuzz      PASS  $(grep -o 'FUZZ OK · [0-9]* cases' /tmp/sweep-fuzz.txt | head -1) · seed $seed"
  else
    say "   fuzz      FAIL  seed $seed"
    grep -E '^  FAIL|FUZZ FAILED' /tmp/sweep-fuzz.txt | head -5 | sed 's/^/             /'
    failures=$((failures + 1))
  fi

  # 3. the harness: spec + fuzz + the static claims about the shipped loop
  if bash scripts/verify_cognition_core.sh >/tmp/sweep-core.txt 2>&1; then
    say "   cognition PASS  $(grep -c '^  ok' /tmp/sweep-core.txt) static claims hold"
  else
    say "   cognition FAIL"
    grep -E 'FAILED|MISSING' /tmp/sweep-core.txt | head -5 | sed 's/^/             /'
    failures=$((failures + 1))
  fi

  # 4. everything else in the repo, so a cognition change cannot quietly break the transport or egress
  if bash scripts/verify_all.sh --quiet >/tmp/sweep-all.txt 2>&1; then
    say "   repo      PASS  $(grep -E '^PASS [0-9]+ ·' /tmp/sweep-all.txt | head -1)"
  else
    say "   repo      FAIL"
    grep -E '^FAIL|failed:' /tmp/sweep-all.txt | head -5 | sed 's/^/             /'
    failures=$((failures + 1))
  fi

  local verdict="OK"
  if [[ $failures -ne 0 ]]; then
    verdict="FAIL($failures)"
    overall=1
  else
    pass=$((pass + 1))
  fi
  printf '%s pass=%s seed=%s %s\n' "$started" "$index" "$seed" "$verdict" >> "$REPORT"
  say "   → $verdict"
}

echo "== cognition sweep · loops=$LOOPS · interval=${INTERVAL}s · report=$REPORT"
index=1
while :; do
  run_pass "$index"
  index=$((index + 1))
  if [[ "$LOOPS" -ne 0 && $index -gt $LOOPS ]]; then break; fi
  [[ "$INTERVAL" -gt 0 ]] && sleep "$INTERVAL"
done

echo
echo "$pass pass(es) clean · report at $REPORT"
if [[ $overall -ne 0 ]]; then
  echo "SWEEP FAILED"
  exit 1
fi
echo "SWEEP OK"
