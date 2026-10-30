package com.lucifer.mobile.verification

import android.content.Context
import android.util.Log
import com.lucifer.mobile.terminal.TerminalExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Runs a planned job on the remote Lean host over Lucifer's existing strict SSH path: password stays in
 * the Keystore-backed secret store, `known_hosts` must be pinned, and the channel is bounded by the
 * job's own timeout.
 *
 * The executor refuses to run a command that does not look exactly like what [JobPlanner] produces.
 * That is not the security boundary — the planner and the source screen are — but a future refactor
 * that interpolates a caller-supplied path into a shell word will fail here instead of in production.
 */
class VmJobExecutor(
    private val context: Context,
    private val transport: String = "vm-ssh"
) : JobExecutor {

    override fun execute(job: LeanJobSpec): JobResult {
        val command = JobPlanner.remoteCommand(job)
        val refusal = JobCommandGuard.check(command, job)
        if (refusal != null) {
            Log.w(TAG, "refused to run ${job.tool.wireName}: $refusal")
            return JobResult(
                ok = false,
                exitCode = -1,
                output = listOf("[VERIFIER] $refusal"),
                errors = listOf(refusal),
                transport = "none"
            )
        }

        val started = System.currentTimeMillis()
        val lines = try {
            // execute() is a synchronous interface because the MCP server calls it from a worker thread
            // in its own pool, and the SSH transport underneath is suspending. That is why bridging with
            // runBlocking is correct *here* — and why a UI action must never call this directly: it would
            // block the frame for up to the job's timeout.
            runBlocking {
                TerminalExecutor(context.applicationContext).executeVmDirect(
                    command = command,
                    timeoutMs = (job.timeoutSeconds + 60).toLong() * 1_000L,
                    maxLines = 400
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "remote elaboration failed", error)
            return JobResult(
                ok = false,
                exitCode = -1,
                output = listOf("[ERROR] The Lean host could not be reached: ${error.message ?: "unknown"}"),
                errors = listOf(error.message ?: error.javaClass.simpleName),
                elapsedMs = System.currentTimeMillis() - started,
                transport = "none"
            )
        }

        val elapsed = System.currentTimeMillis() - started
        val result = ResultParser.parse(lines, transport, elapsed)
        Log.i(TAG, "${job.tool.wireName} · ${job.project.id} · exit ${result.exitCode} · ${elapsed}ms")
        return result
    }

    private companion object {
        const val TAG = "LuciferVerifier"
    }
}
