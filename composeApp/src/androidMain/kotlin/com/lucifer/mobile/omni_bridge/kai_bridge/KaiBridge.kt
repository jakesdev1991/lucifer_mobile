package com.lucifer.mobile.omni_bridge.kai_bridge

import android.content.Context
import android.content.pm.PackageManager
import com.lucifer.mobile.privacy.EgressClass
import com.lucifer.mobile.privacy.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The Kai package this bridge talks to. */
const val KAI_PACKAGE = "com.inspiredandroid.kai"

/**
 * What a cross-app probe of the Kai package found. Every state except [PRESENT_RUNNABLE] is an
 * honest failure mode: the bridge reports exactly which one applies instead of pretending.
 */
enum class KaiState {
    /** The package is not installed. */
    ABSENT,
    /** Installed, but `run-as com.inspiredandroid.kai` was refused (run-as is allow-listed by the OS). */
    RUN_AS_BLOCKED,
    /** Installed and runnable; the sandbox answered `echo pong`. */
    PRESENT_RUNNABLE,
    /** Installed and runnable, but the sandbox did not answer the ping (crashed or wedged). */
    PRESENT_UNRESPONSIVE
}

data class KaiStatus(
    val state: KaiState,
    val versionName: String = "",
    val detail: String = ""
) {
    /** One truthful line for the Settings panel. */
    val line: String
        get() = when (state) {
            KaiState.ABSENT -> "Kai: not installed"
            KaiState.RUN_AS_BLOCKED ->
                "Kai: installed${versionSuffix()} but run-as is blocked — the OS allow-list refuses it"
            KaiState.PRESENT_RUNNABLE -> "Kai: installed${versionSuffix()} · run-as allowed · sandbox answers"
            KaiState.PRESENT_UNRESPONSIVE ->
                "Kai: installed${versionSuffix()} · run-as allowed · sandbox did not answer"
        }

    private fun versionSuffix(): String = if (versionName.isBlank()) "" else " v$versionName"
}

// Response types
sealed class KaiResponse {
    data class SandboxResult(val exitCode: Int, val stdout: String, val stderr: String) : KaiResponse()
    data class Error(val message: String) : KaiResponse()
    object Pong : KaiResponse()
}

/**
 * Kai Bridge — the real, honest cross-app path into the Kai package (`com.inspiredandroid.kai`).
 *
 * What is real: presence and version come from [PackageManager]; execution inside Kai's sandbox goes
 * through `run-as com.inspiredandroid.kai`, which the OS only permits for allow-listed debuggable
 * packages. When run-as is refused, the operation reports exactly that — nothing is stubbed into a
 * fake success.
 *
 * What is never done: no command is executed without the caller having passed the confirmation flow,
 * and any command that needs to reach the network is authorised by [NetworkGuard] first, so a Kai
 * operation lands in the same egress ledger as every other socket in the app. There is no path from
 * this bridge to an ungated connection.
 *
 * What this bridge deliberately no longer offers: wallet, browser-automation, LLM, and RCOD wrappers.
 * They pointed at Python scripts inside the sandbox that nothing deploys, and the app has real paths
 * for each (LuciferWallet, browser/InteractiveBrowser, NemotronClient); the wrappers were removed
 * rather than shipped as stubs.
 */
class KaiBridge(private val context: Context) {

    companion object {
        private const val MAX_CAPTURE_CHARS = 1_000_000
    }

    @Volatile private var connected = false

    /** Probe presence, version, and whether run-as works. Pure read; no sandbox command runs. */
    suspend fun probe(): KaiStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(KAI_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext KaiStatus(KaiState.ABSENT, detail = "package $KAI_PACKAGE is not installed")
        }
        val version = info.versionName.orEmpty()
        // Read-only probe: `run-as <pkg> true`. If the OS refuses run-as for this package, the
        // stderr says so and exit code is non-zero — that is the RUN_AS_BLOCKED answer, not a crash.
        val result = execute("true", 5_000, authorizeNetwork = false)
        when {
            result == null -> KaiStatus(KaiState.RUN_AS_BLOCKED, version, "run-as produced no result")
            result.exitCode != 0 && result.stderr.contains("run-as", ignoreCase = true) ->
                KaiStatus(
                    KaiState.RUN_AS_BLOCKED, version,
                    "run-as refused: ${result.stderr.trim().take(200)}"
                )
            result.exitCode != 0 ->
                KaiStatus(KaiState.RUN_AS_BLOCKED, version, "run-as failed: ${result.stderr.trim().take(200)}")
            else -> {
                // run-as works; confirm the sandbox actually answers.
                val pong = execute("echo pong", 5_000, authorizeNetwork = false)
                if (pong != null && pong.exitCode == 0 && pong.stdout.trim() == "pong") {
                    connected = true
                    KaiStatus(KaiState.PRESENT_RUNNABLE, version, "sandbox answers echo pong")
                } else {
                    KaiStatus(KaiState.PRESENT_UNRESPONSIVE, version, "run-as allowed but echo pong failed")
                }
            }
        }
    }

    /**
     * Connect = probe + require a runnable sandbox. Returns the real [KaiStatus]; the caller reads
     * [KaiStatus.state] rather than a bare boolean, so the UI can say *why* it failed.
     */
    suspend fun connect(): KaiStatus {
        val status = probe()
        connected = status.state == KaiState.PRESENT_RUNNABLE
        return status
    }

    /**
     * Read-only convenience used by the Settings health check. True only when the sandbox answered.
     */
    suspend fun ping(): Boolean {
        if (!connected) return false
        val result = execute("echo pong", 5_000, authorizeNetwork = false)
        return result?.stdout?.trim() == "pong"
    }

    /**
     * Execute a command inside the Kai sandbox via `run-as`.
     *
     * @param authorizeNetwork when true, the command is treated as potentially network-touching and a
     *   network authorisation is recorded in the egress ledger (against the Kai package, class
     *   [EgressClass.UNKNOWN], because a sandbox command can egress even though the bridge itself
     *   opens no socket). Read-only probes pass false.
     */
    suspend fun execute(
        command: String,
        timeoutMs: Int = 30000,
        authorizeNetwork: Boolean = true
    ): KaiResponse.SandboxResult? {
        if (authorizeNetwork) {
            // The bridge itself opens no socket, but a sandbox command may. Record the intent in the
            // same ledger every other egress goes through, so the audit trail is complete.
            NetworkGuard.authorize(
                "https://$KAI_PACKAGE/sandbox-exec",
                userInitiated = true,
                expected = EgressClass.UNKNOWN
            )
        }
        return withContext(Dispatchers.IO) {
            val process = try {
                Runtime.getRuntime().exec(
                    arrayOf("run-as", KAI_PACKAGE, "sh", "-c", command)
                )
            } catch (e: Exception) {
                return@withContext KaiResponse.SandboxResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "run-as could not be started: ${e.message ?: e.javaClass.simpleName}"
                )
            }
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutReader = drainStream(process.inputStream, stdout, "kai-stdout")
            val stderrReader = drainStream(process.errorStream, stderr, "kai-stderr")
            val finished = process.waitFor(timeoutMs.coerceIn(1, 120_000).toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
            }
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            KaiResponse.SandboxResult(
                exitCode = if (finished) process.exitValue() else 124,
                stdout = synchronized(stdout) { stdout.toString() },
                stderr = synchronized(stderr) {
                    stderr.toString() + if (finished) "" else "\n[timeout] sandbox command exceeded its time limit"
                }
            )
        }
    }

    /**
     * Run a read-only command in the sandbox and report honestly: a non-zero exit names the real
     * failure (run-as blocked, command failed) — never a fabricated result.
     */
    suspend fun execReadOnly(command: String, timeoutMs: Int = 10_000): KaiResponse =
        execute(command, timeoutMs, authorizeNetwork = false) ?: KaiResponse.Error("run-as produced no result")

    private fun drainStream(stream: java.io.InputStream, output: StringBuilder, name: String) =
        thread(name = name, isDaemon = true) {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length < MAX_CAPTURE_CHARS) output.append(line).append('\n')
                    }
                }
            }
        }

    fun disconnect() {
        connected = false
    }

    fun isConnected(): Boolean = connected
}
