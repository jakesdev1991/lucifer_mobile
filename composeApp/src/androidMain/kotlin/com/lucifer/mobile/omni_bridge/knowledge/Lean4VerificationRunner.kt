package com.lucifer.mobile.omni_bridge.knowledge

import android.content.Context
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.terminal.TerminalExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-device Omega Theory Lean verification via Lucifer's strict SSH bridge. */
object Lean4VerificationRunner {
    private const val VM_SESSION = "lucifer-lean-verification"
    private const val REMOTE_DIR = ".local/share/lucifer-lean-verifier"
    private const val REPOSITORY = "https://github.com/jakesdev1991/Omega_Theory_Everything.git"
    private const val DOLLAR = "\$"

    data class Snapshot(
        val state: String,
        val output: String,
        val commit: String? = null,
        val toolchain: String? = null
    )

    /** Fetch only source metadata in a detached job; no repository scripts run yet. */
    suspend fun prepare(context: Context): Snapshot = withContext(Dispatchers.IO) {
        if (localVmHost(context) == null) return@withContext setupRequired()
        val command = listOf(
            "set -eu",
            "if ! command -v tmux >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then echo '[SETUP] tmux, git, and coreutils timeout are required in Debian.'; exit 127; fi",
            "if tmux has-session -t $VM_SESSION 2>/dev/null; then echo '[RUNNING] A preparation or verification job is already active.'; exit 3; fi",
            "RUN_DIR=\"${DOLLAR}HOME/$REMOTE_DIR\"",
            "mkdir -p \"${DOLLAR}RUN_DIR\"",
            "echo PREPARING > \"${DOLLAR}RUN_DIR/status\"",
            "echo PREPARING > \"${DOLLAR}RUN_DIR/phase\"",
            "cat > \"${DOLLAR}RUN_DIR/prepare.sh\" <<'LUCIFER_LEAN_PREP'",
            PREPARE_SCRIPT.trimIndent(),
            "LUCIFER_LEAN_PREP",
            "chmod 700 \"${DOLLAR}RUN_DIR/prepare.sh\"",
            "cat > \"${DOLLAR}RUN_DIR/prepare-launch.sh\" <<'LUCIFER_LEAN_PREP_LAUNCH'",
            PREPARATION_LAUNCH_SCRIPT.trimIndent(),
            "LUCIFER_LEAN_PREP_LAUNCH",
            "chmod 700 \"${DOLLAR}RUN_DIR/prepare-launch.sh\"",
            "tmux new-session -d -s $VM_SESSION \"${DOLLAR}RUN_DIR/prepare-launch.sh\"",
            "echo '[STARTED] Source preparation is running in the local Debian VM.'"
        ).joinToString("\n")
        val lines = TerminalExecutor(context.applicationContext).executeVmDirect(command)
        val failed = lines.any { it.startsWith("[ERROR]") || it.startsWith("[SETUP]") } ||
            lines.any { it.contains("[exit ") && !it.endsWith("[exit 0]") }
        snapshotFrom(if (failed) "NOT PREPARED" else "PREPARING", lines)
    }

    /** Run repository audits only for the exact SHA and toolchain shown to the user. */
    suspend fun start(context: Context, expectedCommit: String, expectedToolchain: String): Snapshot = withContext(Dispatchers.IO) {
        if (localVmHost(context) == null) return@withContext setupRequired()
        if (!expectedCommit.matches(Regex("^[0-9a-f]{40}$")) ||
            !expectedToolchain.matches(Regex("^[A-Za-z0-9._:/-]{1,120}$"))) {
            return@withContext Snapshot("NOT STARTED", "Invalid prepared source revision or Lean toolchain.")
        }
        val runScript = VERIFY_SCRIPT
            .replace("__EXPECTED_COMMIT__", expectedCommit)
            .replace("__EXPECTED_TOOLCHAIN__", expectedToolchain)
        val command = listOf(
            "set -eu",
            "if ! command -v tmux >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then echo '[SETUP] tmux and coreutils timeout are required in Debian.'; exit 127; fi",
            "if tmux has-session -t $VM_SESSION 2>/dev/null; then echo '[RUNNING] A verifier job is already active.'; exit 3; fi",
            "RUN_DIR=\"${DOLLAR}HOME/$REMOTE_DIR\"",
            "REPO=\"${DOLLAR}RUN_DIR/Omega_Theory_Everything\"",
            "if [ \"${DOLLAR}(cat \"${DOLLAR}RUN_DIR/status\" 2>/dev/null || true)\" != PREPARED ]; then echo '[FAIL] Source is not in PREPARED state; refresh or prepare again.'; exit 2; fi",
            "if [ \"${DOLLAR}(cat \"${DOLLAR}RUN_DIR/prepared_commit\" 2>/dev/null || true)\" != '$expectedCommit' ]; then echo '[FAIL] Prepared commit does not match the confirmed revision; prepare again.'; exit 2; fi",
            "if [ \"${DOLLAR}(cat \"${DOLLAR}RUN_DIR/prepared_toolchain\" 2>/dev/null || true)\" != '$expectedToolchain' ]; then echo '[FAIL] Prepared toolchain does not match the confirmed pin; prepare again.'; exit 2; fi",
            "cat > \"${DOLLAR}RUN_DIR/run.sh\" <<'LUCIFER_LEAN_RUNNER'",
            runScript.trimIndent(),
            "LUCIFER_LEAN_RUNNER",
            "chmod 700 \"${DOLLAR}RUN_DIR/run.sh\"",
            "cat > \"${DOLLAR}RUN_DIR/run-launch.sh\" <<'LUCIFER_LEAN_RUN_LAUNCH'",
            VERIFICATION_LAUNCH_SCRIPT.trimIndent(),
            "LUCIFER_LEAN_RUN_LAUNCH",
            "chmod 700 \"${DOLLAR}RUN_DIR/run-launch.sh\"",
            "echo RUNNING > \"${DOLLAR}RUN_DIR/status\"",
            "echo VERIFYING > \"${DOLLAR}RUN_DIR/phase\"",
            "tmux new-session -d -s $VM_SESSION \"${DOLLAR}RUN_DIR/run-launch.sh\"",
            "echo '[STARTED] Confirmed source revision launched in local Debian VM.'"
        ).joinToString("\n")
        val lines = TerminalExecutor(context.applicationContext).executeVmDirect(command)
        val failed = lines.any { it.startsWith("[ERROR]") || it.startsWith("[FAIL]") || it.startsWith("[SETUP]") } ||
            lines.any { it.contains("[exit ") && !it.endsWith("[exit 0]") }
        snapshotFrom(if (failed) "NOT STARTED" else "RUNNING", lines)
    }

    suspend fun refresh(context: Context): Snapshot = withContext(Dispatchers.IO) {
        if (localVmHost(context) == null) return@withContext setupRequired()
        val command = listOf(
            "set -u",
            "RUN_DIR=\"${DOLLAR}HOME/$REMOTE_DIR\"",
            "session=INACTIVE; if tmux has-session -t $VM_SESSION 2>/dev/null; then session=ACTIVE; fi",
            "state=${DOLLAR}(cat \"${DOLLAR}RUN_DIR/status\" 2>/dev/null || echo 'NOT RUN')",
            "if [ \"${DOLLAR}session\" = INACTIVE ]; then case \"${DOLLAR}state\" in PREPARING) state=PREPARATION_INTERRUPTED; echo \"${DOLLAR}state\" > \"${DOLLAR}RUN_DIR/status\";; RUNNING|CANCEL_REQUESTED) state=INTERRUPTED; echo \"${DOLLAR}state\" > \"${DOLLAR}RUN_DIR/status\";; esac; fi",
            "echo \"LUCIFER_STATE=${DOLLAR}state\"",
            "echo \"LUCIFER_SESSION=${DOLLAR}session\"",
            "if [ -f \"${DOLLAR}RUN_DIR/prepared_commit\" ]; then echo \"SOURCE_COMMIT=${DOLLAR}(cat \"${DOLLAR}RUN_DIR/prepared_commit\")\"; fi",
            "if [ -f \"${DOLLAR}RUN_DIR/prepared_toolchain\" ]; then echo \"LEAN_TOOLCHAIN=${DOLLAR}(cat \"${DOLLAR}RUN_DIR/prepared_toolchain\")\"; fi",
            "phase=${DOLLAR}(cat \"${DOLLAR}RUN_DIR/phase\" 2>/dev/null || true); echo \"LUCIFER_PHASE=${DOLLAR}phase\"; if [ \"${DOLLAR}phase\" = PREPARING ] || [ \"${DOLLAR}phase\" = PREPARED ]; then if [ -f \"${DOLLAR}RUN_DIR/preparation.log\" ]; then echo '--- preparation output (last 40 lines) ---'; tail -n 40 \"${DOLLAR}RUN_DIR/preparation.log\"; fi; else if [ -f \"${DOLLAR}RUN_DIR/verification.log\" ]; then echo '--- verifier output (last 80 lines) ---'; tail -n 80 \"${DOLLAR}RUN_DIR/verification.log\"; fi; fi"
        ).joinToString("\n")
        snapshotFrom(null, TerminalExecutor(context.applicationContext).executeVmDirect(command))
    }

    suspend fun stop(context: Context): Snapshot = withContext(Dispatchers.IO) {
        if (localVmHost(context) == null) return@withContext setupRequired()
        val command = listOf(
            "set -u",
            "RUN_DIR=\"${DOLLAR}HOME/$REMOTE_DIR\"",
            "if tmux has-session -t $VM_SESSION 2>/dev/null; then",
            "  echo CANCEL_REQUESTED > \"${DOLLAR}RUN_DIR/status\"",
            "  tmux kill-session -t $VM_SESSION",
            "  echo CANCELLED > \"${DOLLAR}RUN_DIR/status\"",
            "  echo '[CANCELLED] Local verification session stopped.'",
            "else echo '[INFO] No active verification session.'; fi",
            "if [ -f \"${DOLLAR}RUN_DIR/status\" ]; then echo \"LUCIFER_STATE=${DOLLAR}(cat \"${DOLLAR}RUN_DIR/status\")\"; fi"
        ).joinToString("\n")
        snapshotFrom(null, TerminalExecutor(context.applicationContext).executeVmDirect(command))
    }

    private fun setupRequired() = Snapshot(
        "SETUP REQUIRED",
        "On-device verification requires VM SSH host 127.0.0.1 or localhost, the configured forwarded port, a verified known_hosts key, and SSH credentials."
    )

    /** Only a hostname that resolves to this device counts as "the VM runs locally". */
    private fun localVmHost(context: Context): String? =
        AppConfig.readText(context, AppConfig.VM_HOST)
            ?.lowercase()
            ?.takeIf { it == "127.0.0.1" || it == "localhost" }

    private fun snapshotFrom(defaultState: String?, lines: List<String>): Snapshot {
        val text = lines.joinToString("\n")
        val state = Regex("(?m)^LUCIFER_STATE=(.+)$").find(text)?.groupValues?.get(1)?.trim()
            ?: defaultState
            ?: when {
                lines.any { it.startsWith("[ERROR]") || it.startsWith("[SECURITY]") } -> "UNAVAILABLE"
                else -> "UNKNOWN"
            }
        val commit = Regex("(?m)^SOURCE_COMMIT=([0-9a-f]{40})$").find(text)?.groupValues?.get(1)
        val toolchain = Regex("(?m)^LEAN_TOOLCHAIN=([A-Za-z0-9._:/-]+)$").find(text)?.groupValues?.get(1)
        return Snapshot(state, text.takeLast(12_000), commit, toolchain)
    }

    private val PREPARATION_LAUNCH_SCRIPT = """#!/usr/bin/env bash
set +e
started=${DOLLAR}SECONDS
timeout --signal=TERM --kill-after=15s 600s "${DOLLAR}HOME/$REMOTE_DIR/prepare.sh"
rc=${DOLLAR}?
elapsed=${DOLLAR}((SECONDS - started))
if [ "${DOLLAR}rc" -ne 0 ] && [ "${DOLLAR}elapsed" -ge 600 ]; then echo PREPARATION_TIMEOUT > "${DOLLAR}HOME/$REMOTE_DIR/status"; echo '[TIMEOUT] Source preparation exceeded 10 minutes.' >> "${DOLLAR}HOME/$REMOTE_DIR/preparation.log"; fi
exit "${DOLLAR}rc"
"""

    private val VERIFICATION_LAUNCH_SCRIPT = """#!/usr/bin/env bash
set +e
started=${DOLLAR}SECONDS
timeout --signal=TERM --kill-after=30s 7200s "${DOLLAR}HOME/$REMOTE_DIR/run.sh"
rc=${DOLLAR}?
elapsed=${DOLLAR}((SECONDS - started))
if [ "${DOLLAR}rc" -ne 0 ] && [ "${DOLLAR}elapsed" -ge 7200 ]; then echo TIMEOUT > "${DOLLAR}HOME/$REMOTE_DIR/status"; echo '[TIMEOUT] Lean verification exceeded two hours.' >> "${DOLLAR}HOME/$REMOTE_DIR/verification.log"; fi
exit "${DOLLAR}rc"
"""

    private val PREPARE_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
RUN_DIR="${DOLLAR}HOME/$REMOTE_DIR"
REPO="${DOLLAR}RUN_DIR/Omega_Theory_Everything"
STATUS="${DOLLAR}RUN_DIR/status"
EXPECTED_REPOSITORY="$REPOSITORY"
echo PREPARING > "${DOLLAR}STATUS"
echo PREPARING > "${DOLLAR}RUN_DIR/phase"
exec > "${DOLLAR}RUN_DIR/preparation.log" 2>&1
fail() { rc=${DOLLAR}?; if [ "${DOLLAR}rc" -ne 0 ]; then echo FAILED > "${DOLLAR}STATUS"; fi; }
trap fail EXIT
echo 'Preparing source only; no repository audit scripts are run in this phase.'
if [ -d "${DOLLAR}REPO/.git" ]; then
  if [ "${DOLLAR}(git -C "${DOLLAR}REPO" remote get-url origin)" != "${DOLLAR}EXPECTED_REPOSITORY" ]; then echo '[FAIL] Existing checkout origin is not the configured Omega Theory repository.'; exit 2; fi
  if [ -n "${DOLLAR}(git -C "${DOLLAR}REPO" status --porcelain)" ]; then echo '[FAIL] Dedicated checkout has local changes; refusing to overwrite.'; exit 2; fi
  git -C "${DOLLAR}REPO" fetch --depth=1 origin main
  COMMIT=${DOLLAR}(git -C "${DOLLAR}REPO" rev-parse FETCH_HEAD)
  git -C "${DOLLAR}REPO" checkout --detach "${DOLLAR}COMMIT"
elif [ -e "${DOLLAR}REPO" ]; then
  echo '[FAIL] Dedicated checkout path exists but is not a Git repository; refusing to overwrite.'
  exit 2
else
  git clone --depth=1 --single-branch --branch main "$REPOSITORY" "${DOLLAR}REPO"
  COMMIT=${DOLLAR}(git -C "${DOLLAR}REPO" rev-parse HEAD)
fi
TOOLCHAIN=${DOLLAR}(cat "${DOLLAR}REPO/lean_proofs/lean-toolchain")
echo "SOURCE_COMMIT=${DOLLAR}COMMIT"
echo "LEAN_TOOLCHAIN=${DOLLAR}TOOLCHAIN"
echo "${DOLLAR}COMMIT" > "${DOLLAR}RUN_DIR/prepared_commit"
echo "${DOLLAR}TOOLCHAIN" > "${DOLLAR}RUN_DIR/prepared_toolchain"
echo PREPARED > "${DOLLAR}STATUS"
echo PREPARED > "${DOLLAR}RUN_DIR/phase"
echo '[PREPARED] Review the exact source SHA and Lean toolchain in Lucifer before starting audits.'
"""

    private val VERIFY_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
RUN_DIR="${DOLLAR}HOME/$REMOTE_DIR"
REPO="${DOLLAR}RUN_DIR/Omega_Theory_Everything"
STATUS="${DOLLAR}RUN_DIR/status"
LOG="${DOLLAR}RUN_DIR/verification.log"
EXPECTED_COMMIT="__EXPECTED_COMMIT__"
EXPECTED_TOOLCHAIN="__EXPECTED_TOOLCHAIN__"
EXPECTED_REPOSITORY="$REPOSITORY"
echo RUNNING > "${DOLLAR}STATUS"
echo VERIFYING > "${DOLLAR}RUN_DIR/phase"
exec > "${DOLLAR}LOG" 2>&1
finish() {
  rc=${DOLLAR}?
  current=${DOLLAR}(cat "${DOLLAR}STATUS" 2>/dev/null || true)
  if [ "${DOLLAR}current" = CANCEL_REQUESTED ] || [ "${DOLLAR}current" = CANCELLED ]; then result=CANCELLED
  elif [ "${DOLLAR}rc" -eq 0 ]; then result=PASS
  else result=FAIL
  fi
  echo "${DOLLAR}result" > "${DOLLAR}STATUS"
  echo "VERIFICATION_RESULT=${DOLLAR}result EXIT_CODE=${DOLLAR}rc"
}
trap finish EXIT
if [ ! -d "${DOLLAR}REPO/.git" ]; then echo '[FAIL] Prepared Git checkout is missing.'; exit 2; fi
if [ "${DOLLAR}(git -C "${DOLLAR}REPO" remote get-url origin)" != "${DOLLAR}EXPECTED_REPOSITORY" ]; then echo '[FAIL] Prepared checkout origin changed.'; exit 2; fi
if [ -n "${DOLLAR}(git -C "${DOLLAR}REPO" status --porcelain)" ]; then echo '[FAIL] Prepared checkout has local modifications.'; exit 2; fi
ACTUAL_COMMIT=${DOLLAR}(git -C "${DOLLAR}REPO" rev-parse HEAD)
if [ "${DOLLAR}ACTUAL_COMMIT" != "${DOLLAR}EXPECTED_COMMIT" ]; then echo '[FAIL] Checked-out SHA differs from the user-confirmed SHA.'; exit 2; fi
if [ "${DOLLAR}(cat "${DOLLAR}REPO/lean_proofs/lean-toolchain")" != "${DOLLAR}EXPECTED_TOOLCHAIN" ]; then echo '[FAIL] lean-toolchain differs from the user-confirmed pin.'; exit 2; fi
echo '=== Omega Theory local Lean 4 verification ==='
echo "SOURCE_COMMIT=${DOLLAR}ACTUAL_COMMIT"
cd "${DOLLAR}REPO/lean_proofs"
TOOLCHAIN=${DOLLAR}(cat lean-toolchain)
EXPECTED_LEAN=${DOLLAR}{TOOLCHAIN#leanprover/lean4:}
echo "LEAN_TOOLCHAIN=${DOLLAR}TOOLCHAIN"
echo "LEAN_VERSION=${DOLLAR}(lean --version)"
lake --version
if ! lean --version | grep -Fq "${DOLLAR}EXPECTED_LEAN"; then echo '[FAIL] Installed Lean version does not match the repository pin.'; exit 2; fi
python3 -m unittest discover -s . -p 'test_*.py'
if grep -rn --include='*.lean' --exclude-dir=.lake -E '\bsorry\b|\badmit\b|\bnative_decide\b|\bsorryAx\b|@\[extern|\bimplemented_by\b|\bopaque\b|\bunsafe\b|^\s*partial\s+def\b|: True :=|:= by trivial' .; then
  echo '[FAIL] Found forbidden sorry/admit/native_decide/extern/opaque/unsafe/partial or trivial stubs.'
  exit 1
fi
python3 audit_axioms.py --max 0
python3 check_declaration_parity.py
python3 sync_txt_mirrors.py
python3 audit_vacuity.py --max-misleading 0 --max-axiom-names 0 --max-unit-stubs 0 --max-unit-types 0 --max-decorative-hypotheses 0 --max-trivial-hypotheses 0 --max-identity-proofs 0 --max-trivial-proofs 0 --alias-baseline legacy_statement_aliases.json
lake build ToE
python3 check_statement_audit.py --repo .
mathlib=""
for candidate in .lake/packages/mathlib4 .lake/packages/mathlib; do if [ -d "${DOLLAR}candidate/Mathlib" ]; then mathlib=${DOLLAR}candidate; break; fi; done
if [ -z "${DOLLAR}mathlib" ]; then mathlib="${DOLLAR}(find .lake/packages -maxdepth 2 -type d -name Mathlib -print -quit)"; mathlib=${DOLLAR}{mathlib%/Mathlib}; fi
if [ -z "${DOLLAR}mathlib" ] || [ ! -d "${DOLLAR}mathlib/Mathlib" ]; then echo '[FAIL] Pinned Mathlib source checkout is missing after lake build.'; exit 2; fi
lean_prefix=${DOLLAR}(lean --print-prefix)
lean_src="${DOLLAR}lean_prefix/src/lean"
if [ ! -d "${DOLLAR}lean_src/Init" ]; then echo "[FAIL] Lean core sources missing at ${DOLLAR}lean_src."; exit 2; fi
python3 check_mathlib_names.py --mathlib "${DOLLAR}mathlib" --lean "${DOLLAR}lean_src" --repo . --max 0
python3 audit_kernel.py
echo 'ALL_LOCAL_LEAN_AND_SOURCE_GATES_PASSED'
echo "VERIFIED_COMMIT=${DOLLAR}ACTUAL_COMMIT"
"""
}
