package com.lucifer.mobile.verification

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.security.SecretStore

/**
 * The owner's handle on the verifier: one place that knows where state lives, how the endpoint is
 * started, and what the World tab should draw.
 *
 * Keys are stored through [SecretStore] (AES-GCM under a Keystore key) even though only their hashes
 * are written, because the ledger is revenue data and the project list is a map of Lucifer's Lean
 * checkouts. Neither belongs in a plain file a backup tool can read.
 */
object VerifierController {

    private const val TAG = "LuciferVerifier"

    @Volatile
    private var runtimeRef: VerifierRuntime? = null

    @Volatile
    private var endpointRef: VerifierEndpoint? = null

    /** Where the server listens. Loopback only: exposure is a separate, visible decision. */
    const val PORT = ExposurePlan.DEFAULT_PORT

    class AndroidStore(private val context: Context) : VerifierStore {
        override fun read(name: String): String? = SecretStore.read(context, name)
        override fun write(name: String, value: String) {
            if (!SecretStore.write(context, name, value)) {
                Log.w(TAG, "could not persist $name")
            }
        }
    }

    fun runtime(context: Context): VerifierRuntime = synchronized(this) {
        val app = context.applicationContext
        runtimeRef ?: VerifierRuntime(
            store = AndroidStore(app),
            executor = VmJobExecutor(app)
        ).also { runtimeRef = it }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        runtime(app)
        app.startForegroundService(
            Intent(app, VerificationService::class.java).setAction(VerificationService.ACTION_START)
        )
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        app.startService(
            Intent(app, VerificationService::class.java).setAction(VerificationService.ACTION_STOP)
        )
    }

    /** Called by the service. Returns the bound port, or -1 if another process holds it. */
    fun startEndpoint(context: Context, port: Int = PORT): Int = synchronized(this) {
        val existing = endpointRef
        if (existing?.running == true) return existing.boundPort
        val endpoint = VerifierEndpoint(runtime(context), port = port)
        val bound = endpoint.start()
        if (bound <= 0) {
            Log.e(TAG, "port $port is unavailable")
            return -1
        }
        endpointRef = endpoint
        bound
    }

    fun stopEndpoint() = synchronized(this) {
        endpointRef?.stop()
        endpointRef = null
    }

    val endpointRunning: Boolean get() = endpointRef?.running == true

    fun state(
        context: Context,
        message: String? = null,
        busy: Boolean = false,
        issuedToken: String? = null,
        exposure: String = "loopback only · no tunnel running"
    ): VerifierUiState = VerifierUiState.from(
        runtime = runtime(context),
        running = VerificationService.isRunning && endpointRunning,
        port = endpointRef?.boundPort?.takeIf { it > 0 } ?: PORT,
        exposure = exposure,
        issuedToken = issuedToken,
        message = message,
        busy = busy
    )

    fun issueInternalKey(context: Context): ApiKeys.Issued? =
        runtime(context).ensureInternalKey()

    fun issueKey(context: Context, plan: Plan): ApiKeys.Issued =
        runtime(context).issueKey(
            label = "owner-issued ${plan.name.lowercase()}",
            plan = plan,
            scopes = setOf(Scope.VERIFY, Scope.READ) +
                if (plan == Plan.INTERNAL || plan == Plan.ENTERPRISE) setOf(Scope.BUILD) else emptySet()
        )

    fun revokeKey(context: Context, keyId: String): Boolean =
        runtime(context).revokeKey(keyId)

    fun setProjectRoot(context: Context, root: String): Boolean =
        runtime(context).setProjectRoot(VerifierDefaults.toe.id, root)

    /**
     * Lucifer checking something with his own identity and no token — the path a bounty reproduction, a
     * workflow gate, or a theory-of-everything change uses. It is the same pipeline as a customer call:
     * planning, the source screen, the quota rule and the executor all run; only authentication is
     * internal, and the tool is free on the internal plan.
     */
    fun ownerTool(
        context: Context,
        tool: VerifierTool,
        project: String = VerifierDefaults.toe.id,
        module: String? = null,
        source: String? = null,
        declarations: List<String> = emptyList(),
        timeoutSeconds: Int? = null
    ): JobResult? {
        val arguments = linkedMapOf<String, Any?>("project" to project)
        if (module != null) arguments["module"] = module
        if (source != null) arguments["source"] = source
        if (declarations.isNotEmpty()) arguments["declarations"] = declarations
        if (timeoutSeconds != null) arguments["timeout_seconds"] = timeoutSeconds
        val body = JsonLite.stringify(
            linkedMapOf<String, Any?>(
                "jsonrpc" to "2.0",
                "id" to "owner",
                "method" to "tools/call",
                "params" to linkedMapOf<String, Any?>("name" to tool.wireName, "arguments" to arguments)
            )
        )
        return runtime(context).ownerCall(body).result
    }

    /** The common case: check a theorem Lucifer just wrote, against his own project. */
    fun ownerCheck(context: Context, source: String, module: String? = null): JobResult? =
        ownerTool(context, VerifierTool.CHECK, module = module, source = source)

    /**
     * The command that would publish the loopback endpoint. It is returned as text, never executed
     * here: starting a tunnel is a visible terminal action the owner confirms like any other.
     */
    fun exposureCommand(hostname: String?): String? =
        hostname?.trim()?.takeIf { it.isNotEmpty() }?.let { host ->
            runCatching { ExposurePlan.cloudflare(host, PORT).command }.getOrNull()
        }
}
