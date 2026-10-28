package com.lucifer.mobile.verification

import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.bool
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.str
import com.lucifer.mobile.resilience.strOrNull

/**
 * The running verifier: keys, meters, the ledger, the configured projects, and the one entry point a
 * network request goes through.
 *
 * Everything here is plain Kotlin on purpose. `VerificationCore` decides *what* is allowed; this file
 * holds the *state* those decisions are made against, and the harness drives it end to end: issue a
 * key, spend credits, roll the period, revoke the key, persist, reload.
 *
 * State lives behind [VerifierStore] so the Android side can put it in `SecretStore` (Keystore-backed)
 * and the harness can put it in a `HashMap`.
 */
interface VerifierStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
}

/** In-memory store, used by the harness and as a fallback when files cannot be written. */
class MemoryVerifierStore(initial: Map<String, String> = emptyMap()) : VerifierStore {
    private val values = LinkedHashMap<String, String>(initial)
    override fun read(name: String): String? = values[name]
    override fun write(name: String, value: String) {
        values[name] = value
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Persistence
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * JSON codecs for the things that survive a reboot. Key records store only the SHA-256 of the token,
 * so the file being read does not let its reader call the server.
 *
 * Anything malformed is dropped, never guessed at: a corrupt key file must not resurrect a key, and a
 * corrupt ledger must not invent revenue.
 */
object VerifierCodec {

    const val KEYS_FILE = "verifier/keys.json"
    const val METERS_FILE = "verifier/meters.json"
    const val LEDGER_FILE = "verifier/ledger.json"
    const val PROJECTS_FILE = "verifier/projects.json"

    fun encodeKeys(records: List<ApiKeyRecord>): String = JsonLite.stringify(
        records.map { record ->
            linkedMapOf<String, Any?>(
                "id" to record.id,
                "prefix" to record.prefix,
                "hash" to record.hash,
                "plan" to record.plan.name,
                "scopes" to record.scopes.map { it.name }.sorted(),
                "label" to record.label,
                "createdAt" to record.createdAt,
                "revoked" to record.revoked
            )
        }
    )

    fun decodeKeys(raw: String?): List<ApiKeyRecord> = objects(raw).mapNotNull { entry ->
        val id = entry.str("id")
        val hash = entry.str("hash")
        if (id.isBlank() || hash.length != 64) return@mapNotNull null
        val plan = runCatching { Plan.valueOf(entry.str("plan")) }.getOrNull() ?: return@mapNotNull null
        val scopes = entry.arr("scopes")
            .mapNotNull { scope -> runCatching { Scope.valueOf(scope.toString()) }.getOrNull() }
            .toSet()
        if (scopes.isEmpty()) return@mapNotNull null
        ApiKeyRecord(
            id = id,
            prefix = entry.str("prefix").ifBlank { "${ApiKeys.PREFIX}_$id" },
            hash = hash,
            plan = plan,
            scopes = scopes,
            label = entry.str("label").take(60).ifBlank { "key $id" },
            createdAt = entry.long("createdAt"),
            revoked = entry.bool("revoked")
        )
    }.distinctBy { it.id }

    fun encodeMeters(meters: Collection<MeterState>): String = JsonLite.stringify(
        meters.map { meter ->
            linkedMapOf<String, Any?>(
                "keyId" to meter.keyId,
                "plan" to meter.plan.name,
                "used" to meter.usedCredits,
                "periodStart" to meter.periodStartEpochMs,
                "lifetime" to meter.lifetimeCredits
            )
        }
    )

    fun decodeMeters(raw: String?): List<MeterState> = objects(raw).mapNotNull { entry ->
        val keyId = entry.str("keyId")
        if (keyId.isBlank()) return@mapNotNull null
        val plan = runCatching { Plan.valueOf(entry.str("plan")) }.getOrNull() ?: return@mapNotNull null
        MeterState(
            keyId = keyId,
            plan = plan,
            usedCredits = entry.int("used").coerceAtLeast(0),
            periodStartEpochMs = entry.long("periodStart").coerceAtLeast(0L),
            lifetimeCredits = entry.int("lifetime").coerceAtLeast(0)
        )
    }.distinctBy { it.keyId }

    fun encodeLedger(lines: List<UsageLine>): String = JsonLite.stringify(
        lines.map { line ->
            linkedMapOf<String, Any?>(
                "at" to line.at,
                "keyId" to line.keyId,
                "tool" to line.tool.wireName,
                "credits" to line.credits,
                "usd" to line.usd,
                "ok" to line.ok
            )
        }
    )

    fun decodeLedger(raw: String?): List<UsageLine> = objects(raw).mapNotNull { entry ->
        val tool = VerifierTool.entries.firstOrNull { it.wireName == entry.str("tool") } ?: return@mapNotNull null
        val keyId = entry.str("keyId")
        if (keyId.isBlank()) return@mapNotNull null
        UsageLine(
            at = entry.long("at"),
            keyId = keyId,
            tool = tool,
            credits = entry.int("credits").coerceAtLeast(0),
            usd = (entry["usd"] as? Number)?.toDouble() ?: 0.0,
            ok = entry.bool("ok")
        )
    }

    fun encodeProjects(projects: List<LeanProject>): String = JsonLite.stringify(
        projects.map { project ->
            linkedMapOf<String, Any?>(
                "id" to project.id,
                "label" to project.label,
                "root" to project.root,
                "module" to project.defaultModule,
                "toolchain" to project.toolchain,
                "public" to project.publicOk
            )
        }
    )

    fun decodeProjects(raw: String?): List<LeanProject> = objects(raw).mapNotNull { entry ->
        val id = entry.str("id")
        val root = entry.str("root")
        if (!LeanProject.validId(id) || !LeanProject.validRoot(root)) return@mapNotNull null
        LeanProject(
            id = id,
            label = entry.str("label").take(60).ifBlank { id },
            root = root,
            defaultModule = entry.strOrNull("module")?.takeIf { LeanProject.validModule(it) },
            toolchain = entry.strOrNull("toolchain")?.take(60),
            publicOk = entry["public"] as? Boolean ?: true
        )
    }.distinctBy { it.id }

    private fun objects(raw: String?): List<Map<String, Any?>> {
        val parsed = runCatching { JsonLite.parse(raw) }.getOrNull() as? List<*> ?: return emptyList()
        return parsed.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }
        }
    }
}

/**
 * What a fresh install is allowed to touch. One project, Lucifer's own theory-of-everything checkout on
 * the configured Lean host. A client can name it by id and nothing else: the root path never comes from
 * the wire.
 */
object VerifierDefaults {

    data class Project(
        val id: String,
        val label: String,
        val root: String,
        val module: String?
    )

    val toe = LeanProject(
        id = "toe",
        label = "Omega Theory of Everything",
        root = "/srv/lean/Omega_Theory_Everything",
        defaultModule = "ToE"
    )

    fun projects(): List<LeanProject> = listOf(toe)

    /** The project Lucifer's own keys use for bounties and workflow proofs. */
    const val INTERNAL_KEY_LABEL = "lucifer · bounty and proof work"
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Runtime
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One lock, one owner of the state files. Every mutation re-reads nothing and rewrites everything: the
 * files are small and losing a meter is worse than a redundant write.
 */
class VerifierRuntime(
    private val store: VerifierStore,
    private val executor: JobExecutor?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val jobIds: () -> String = { "job-" + java.util.UUID.randomUUID().toString().take(8) }
) {

    data class Usage(
        val credits: Int,
        val usd: Double,
        val lines: Int,
        val byKey: Map<String, Int>
    ) {
        fun describe(): String =
            if (lines == 0) "No verification work metered yet."
            else "$credits credits metered · $${"%.2f".format(usd)} billable · $lines lines"
    }

    private val lock = Any()
    private val keys = VerifierCodec.decodeKeys(store.read(VerifierCodec.KEYS_FILE)).toMutableList()
    private val meters = VerifierCodec.decodeMeters(store.read(VerifierCodec.METERS_FILE))
        .associateBy { it.keyId }
        .toMutableMap()
    private var ledger = VerifierCodec.decodeLedger(store.read(VerifierCodec.LEDGER_FILE))
    private var configured = VerifierCodec.decodeProjects(store.read(VerifierCodec.PROJECTS_FILE))
        .ifEmpty { VerifierDefaults.projects() }

    val keyCount: Int get() = synchronized(lock) { keys.size }
    val activeKeyCount: Int get() = synchronized(lock) { keys.count { !it.revoked } }

    fun projects(): List<LeanProject> = synchronized(lock) { configured.toList() }

    fun keys(): List<ApiKeyRecord> = synchronized(lock) { keys.toList() }

    /**
     * Issues a key and hands back the only copy of its token that will ever exist. `ADMIN` implies the
     * other scopes; an empty scope set would be a key that can do nothing, so it is widened to `VERIFY`.
     */
    fun issueKey(label: String, plan: Plan, scopes: Set<Scope> = setOf(Scope.VERIFY)): ApiKeys.Issued =
        synchronized(lock) {
            val issued = ApiKeys.issue(label, plan, scopes, clock())
            keys += issued.record
            meters[issued.record.id] = MeterState(
                keyId = issued.record.id,
                plan = plan,
                periodStartEpochMs = clock()
            )
            persistKeys()
            persistMeters()
            issued
        }

    fun revokeKey(keyId: String): Boolean = synchronized(lock) {
        val index = keys.indexOfFirst { it.id == keyId }
        if (index < 0) return false
        if (keys[index].revoked) return false
        keys[index] = keys[index].copy(revoked = true)
        persistKeys()
        true
    }

    fun meterFor(keyId: String): MeterState? = synchronized(lock) {
        val meter = meters[keyId] ?: keys.firstOrNull { it.id == keyId }?.let { key ->
            MeterState(keyId = key.id, plan = key.plan, periodStartEpochMs = clock())
        } ?: return null
        Billing.roll(meter, clock())
    }

    fun meters(): List<MeterState> = synchronized(lock) {
        val now = clock()
        keys.map { key ->
            Billing.roll(
                meters[key.id] ?: MeterState(keyId = key.id, plan = key.plan, periodStartEpochMs = now),
                now
            )
        }
    }

    fun usage(afterEpochMs: Long = 0L): Usage = synchronized(lock) {
        val window = ledger.filter { it.at >= afterEpochMs }
        Usage(
            credits = window.sumOf { it.credits },
            usd = window.sumOf { it.usd },
            lines = window.size,
            byKey = window.groupBy { it.keyId }.mapValues { entry -> entry.value.sumOf { it.credits } }
        )
    }

    fun ledger(limit: Int = 50): List<UsageLine> = synchronized(lock) { ledger.takeLast(limit) }

    /** Re-points a configured project at a new checkout. The id and the module stay put. */
    fun setProjectRoot(id: String, root: String): Boolean = synchronized(lock) {
        val trimmed = root.trim()
        if (!LeanProject.validRoot(trimmed)) return false
        val index = configured.indexOfFirst { it.id == id }
        if (index < 0) return false
        configured = configured.toMutableList().also { it[index] = it[index].copy(root = trimmed) }
        store.write(VerifierCodec.PROJECTS_FILE, VerifierCodec.encodeProjects(configured))
        true
    }

    /** Replaces the project list from local configuration; malformed entries are dropped. */
    fun replaceProjects(projects: List<LeanProject>): List<LeanProject> = synchronized(lock) {
        val clean = projects.filter { LeanProject.validId(it.id) && LeanProject.validRoot(it.root) }
            .distinctBy { it.id }
        if (clean.isNotEmpty()) {
            configured = clean
            store.write(VerifierCodec.PROJECTS_FILE, VerifierCodec.encodeProjects(clean))
        }
        configured.toList()
    }

    /**
     * The single door a network request comes through.
     *
     * A request without a valid key reaches only the handshake; everything else is refused by
     * [McpProtocol] before any planning. Work is **charged after it happens** — a crash loses revenue
     * rather than billing for a proof nobody checked — and a job that never reached a host
     * (`transport == "none"`) is never charged at all.
     */
    fun handle(raw: String, authorization: String?): McpProtocol.Handling {
        val token = ApiKeys.tokenFromHeader(authorization)
        val key = token?.let { presented -> synchronized(lock) { ApiKeys.find(keys, presented) } }
        return dispatch(raw, key)
    }

    /**
     * Lucifer's own path through the same gates.
     *
     * The owner process does not present a bearer token to itself: it resolves its internal key record
     * directly, so the planner, the source screen, the quota rule and the executor all still apply. That
     * is also why the internal token can stay shown-once — nothing needs to keep it around to call in.
     */
    fun ownerCall(raw: String): McpProtocol.Handling {
        val record = synchronized(lock) {
            keys.firstOrNull { it.plan == Plan.INTERNAL && !it.revoked }
        } ?: ensureInternalKey()?.record
        return dispatch(raw, record)
    }

    private fun dispatch(raw: String, key: ApiKeyRecord?): McpProtocol.Handling {
        val now = clock()
        val handling = McpProtocol.handle(
            raw = raw,
            caller = key?.let { record ->
                val meter = meterFor(record.id) ?: MeterState(record.id, record.plan, periodStartEpochMs = now)
                McpProtocol.Caller(record, meter)
            },
            projects = projects(),
            now = now,
            idFactory = jobIds,
            executor = executor
        )
        val job = handling.job
        val ran = handling.result?.transport != null && handling.result.transport != "none"
        if (key != null && job != null && handling.charge > 0 && ran) {
            synchronized(lock) {
                val meter = Billing.roll(
                    meters[key.id] ?: MeterState(key.id, key.plan, periodStartEpochMs = now),
                    now
                )
                meters[key.id] = Billing.apply(meter, handling.charge)
                ledger = (ledger + UsageLine(
                    at = now,
                    keyId = key.id,
                    tool = job.tool,
                    credits = handling.charge,
                    usd = Billing.usdFor(handling.charge, key.plan),
                    ok = handling.result?.ok == true
                )).takeLast(MeteringLedger.MAX_LINES)
                persistMeters()
                store.write(VerifierCodec.LEDGER_FILE, VerifierCodec.encodeLedger(ledger))
            }
        }
        return handling
    }

    /** A key that can spend Lucifer's own budget. Only issued on an explicit owner action. */
    fun ensureInternalKey(): ApiKeys.Issued? = synchronized(lock) {
        if (keys.any { it.plan == Plan.INTERNAL && !it.revoked }) return null
        issueKey(
            label = VerifierDefaults.INTERNAL_KEY_LABEL,
            plan = Plan.INTERNAL,
            scopes = setOf(Scope.ADMIN, Scope.VERIFY, Scope.BUILD, Scope.READ)
        )
    }

    private fun persistKeys() {
        store.write(VerifierCodec.KEYS_FILE, VerifierCodec.encodeKeys(keys))
    }

    private fun persistMeters() {
        store.write(VerifierCodec.METERS_FILE, VerifierCodec.encodeMeters(meters.values))
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// UI state (kept out of Compose so the harness can shape it)
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** What Lucifer is allowed to ask the verifier to do from the World tab. */
sealed interface VerifierUiCommand {
    data object Start : VerifierUiCommand
    data object Stop : VerifierUiCommand
    data object Refresh : VerifierUiCommand
    data object IssueInternalKey : VerifierUiCommand
    data class IssueKey(val plan: Plan) : VerifierUiCommand
    data class RevokeKey(val keyId: String) : VerifierUiCommand
    data class SetProjectRoot(val root: String) : VerifierUiCommand
}

data class VerifierKeyView(
    val id: String,
    val label: String,
    val plan: String,
    val scopes: String,
    val used: Int,
    val remaining: Int?,
    val revoked: Boolean
)

/**
 * The panel's view of the server. `endpoint` is always the loopback bind address — the public hostname
 * is `exposureHost` and is only reachable once a tunnel is running, which is deliberate: the phone does
 * not open a port to the internet on its own.
 */
data class VerifierUiState(
    val running: Boolean = false,
    val port: Int = ExposurePlan.DEFAULT_PORT,
    val projects: List<String> = emptyList(),
    val keys: List<VerifierKeyView> = emptyList(),
    val usedCredits: Int = 0,
    val billableUsd: Double = 0.0,
    val ledgerLines: Int = 0,
    val exposure: String = "loopback only · no tunnel running",
    val issuedToken: String? = null,
    val busy: Boolean = false,
    val message: String? = null
) {
    val endpoint: String get() = "http://127.0.0.1:$port/mcp"

    companion object {
        fun from(
            runtime: VerifierRuntime,
            running: Boolean,
            port: Int = ExposurePlan.DEFAULT_PORT,
            exposure: String = "loopback only · no tunnel running",
            issuedToken: String? = null,
            message: String? = null,
            busy: Boolean = false
        ): VerifierUiState {
            val meters = runtime.meters().associateBy { it.keyId }
            val usage = runtime.usage()
            return VerifierUiState(
                running = running,
                port = port,
                projects = runtime.projects().map { "${it.id} → ${it.root}" },
                keys = runtime.keys().map { key ->
                    val meter = meters[key.id]
                    VerifierKeyView(
                        id = key.id,
                        label = key.label,
                        plan = key.plan.name,
                        scopes = key.scopes.map { it.name }.sorted().joinToString("/"),
                        used = meter?.usedCredits ?: 0,
                        remaining = meter?.remaining?.takeIf { it != Int.MAX_VALUE },
                        revoked = key.revoked
                    )
                },
                usedCredits = usage.credits,
                billableUsd = usage.usd,
                ledgerLines = usage.lines,
                exposure = exposure,
                issuedToken = issuedToken,
                busy = busy,
                message = message
            )
        }
    }
}
