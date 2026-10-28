package com.lucifer.mobile.verification

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.security.SecretStore
import java.security.SecureRandom

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

    @Volatile
    private var probeRef: HealthProbe? = null

    /** Where the audit-vault deployment lives, plus the shared secret the phone generated for it. */
    data class VaultConfig(val url: String, val secret: String)

    private const val VAULT_FILE = "verification/vault.json"

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

    /**
     * The runtime, wired to both backends: audit-vault first when it is linked and answering, the
     * Debian VM otherwise. Keys, scopes, quota and the ledger stay here in either case.
     */
    fun runtime(context: Context): VerifierRuntime = synchronized(this) {
        val app = context.applicationContext
        runtimeRef ?: run {
            val vm = VmJobExecutor(app)
            val vault = vaultConfig(app)?.let { config ->
                val transport = HttpVaultTransport(config.url)
                val probe = HealthProbe(HttpVaultTransport(config.url, timeoutMs = 4_000))
                probeRef = probe
                AuditVaultExecutor(transport, config.secret) to probe
            }
            val executor = if (vault == null) {
                vm
            } else {
                ExecutorRouter(
                    primary = vault.first,
                    fallback = vm,
                    primaryAvailable = { vault.second.available() }
                )
            }
            VerifierRuntime(store = AndroidStore(app), executor = executor).also { runtimeRef = it }
        }
    }

    private fun resetRuntime() = synchronized(this) {
        runtimeRef = null
        probeRef = null
    }

    /**
     * Links an audit-vault deployment. The shared secret is generated **here**, on the phone, and shown
     * once: the vault is given a way to accept Lucifer's jobs, never a way to spend a customer's credits.
     */
    fun linkVault(context: Context, url: String): VaultConfig? = synchronized(this) {
        val app = context.applicationContext
        if (!AuditVaultContract.validUrl(url)) return null
        val normalized = AuditVaultContract.normalizeUrl(url)
        val existing = vaultConfig(app)?.takeIf { it.url == normalized }
        val secret = existing?.secret ?: newVaultSecret()
        val persisted = SecretStore.write(
            app,
            VAULT_FILE,
            JsonLite.stringify(linkedMapOf<String, Any?>("url" to normalized, "secret" to secret))
        )
        if (!persisted) {
            Log.w(TAG, "could not persist the vault link")
            return null
        }
        resetRuntime()
        VaultConfig(normalized, secret)
    }

    fun unlinkVault(context: Context) = synchronized(this) {
        SecretStore.delete(context.applicationContext, VAULT_FILE)
        resetRuntime()
    }

    fun vaultConfig(context: Context): VaultConfig? {
        val raw = SecretStore.read(context.applicationContext, VAULT_FILE) ?: return null
        val entry = runCatching { JsonLite.parseObject(raw) }.getOrNull() ?: return null
        val url = (entry["url"] as? String)?.trim().orEmpty()
        val secret = (entry["secret"] as? String)?.trim().orEmpty()
        if (!AuditVaultContract.validUrl(url) || secret.length < 32) return null
        return VaultConfig(AuditVaultContract.normalizeUrl(url), secret)
    }

    /**
     * Backend status for the panel. It probes at most once per 30 s, so drawing the panel does not hammer
     * a host that is already down.
     */
    fun vaultStatus(context: Context): String {
        val config = vaultConfig(context) ?: return "not linked · using the Debian VM"
        runtime(context)
        val probe = probeRef ?: return "linked · ${config.url} · unprobed"
        return probe.describe() + " · " + config.url
    }

    fun backendStatus(context: Context): String {
        val config = vaultConfig(context) ?: return "vm-ssh"
        runtime(context)
        return if (probeRef?.available() == true) "audit-vault" else "audit-vault down → vm-ssh"
    }

    private fun newVaultSecret(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return bytes.joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
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
        vaultSecret: String? = null,
        exposure: String = "loopback only · no tunnel running"
    ): VerifierUiState = VerifierUiState.from(
        runtime = runtime(context),
        running = VerificationService.isRunning && endpointRunning,
        port = endpointRef?.boundPort?.takeIf { it > 0 } ?: PORT,
        exposure = exposure,
        vault = vaultStatus(context),
        backend = backendStatus(context),
        issuedToken = issuedToken,
        vaultSecret = vaultSecret,
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

    /**
     * The audit gate, run by Lucifer before anything is called a finding. Returns null only when the
     * call never reached the pipeline; a gate report with blockers is the normal, useful answer.
     */
    fun ownerGates(
        context: Context,
        source: String,
        declarations: List<String> = emptyList(),
        module: String? = null
    ): StatementGates.Report? = ownerTool(
        context = context,
        tool = VerifierTool.GATES,
        module = module,
        source = source,
        declarations = declarations
    )?.let { result ->
        StatementGates.report(
            source = source,
            claimed = declarations,
            projectPlaceholders = result.sorryCount,
            parityFound = result.parity
        )
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
