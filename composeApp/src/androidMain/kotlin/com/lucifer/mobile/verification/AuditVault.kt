package com.lucifer.mobile.verification

import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.resilience.arr
import com.lucifer.mobile.resilience.bool
import com.lucifer.mobile.resilience.int
import com.lucifer.mobile.resilience.long
import com.lucifer.mobile.resilience.obj
import com.lucifer.mobile.resilience.str
import com.lucifer.mobile.privacy.NetworkGuard
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The contract between Lucifer's phone and an **audit-vault** verification backend.
 *
 * The split of responsibility is the point of this file:
 *
 * - **The phone owns identity and money.** Keys, scopes, quota enforcement and the ledger live in
 *   [VerifierRuntime] and never leave the device. The vault has no customer keys and cannot bill
 *   anyone.
 * - **The vault owns compute only.** It receives a *structured job* — never a shell string — runs it,
 *   and returns a verdict plus an attestation of what it actually ran. It is treated as untrusted: the
 *   phone recomputes the verdict from the primitives and refuses to accept a result whose attestation
 *   does not match the bytes it sent.
 *
 * Because the vault is semi-trusted remote compute, requests are signed:
 * `X-Lucifer-Signature = hex(HMAC-SHA256(shared secret, "<timestamp>\n<body>"))`, with the shared secret
 * generated on the phone and pasted into the vault's configuration once. A receiver must reject a
 * timestamp more than 300 s from its own clock, which is why the header is millisecond Unix time.
 *
 * Nothing here touches Android, so the harness drives it against a fake transport and checks the bytes.
 */
object AuditVaultContract {

    const val VERSION = 1
    const val PATH_HEALTH = "/v1/health"
    const val PATH_JOBS = "/v1/jobs"
    const val HEADER_CONTRACT = "X-Lucifer-Contract"
    const val HEADER_JOB = "X-Lucifer-Job"
    const val HEADER_TIMESTAMP = "X-Lucifer-Timestamp"
    const val HEADER_SIGNATURE = "X-Lucifer-Signature"
    const val MAX_SKEW_MILLIS = 300_000L
    const val MAX_BODY_BYTES = 400_000
    const val MAX_OUTPUT_LINES = 400

    data class Health(
        val name: String,
        val version: String,
        val tools: Set<String>,
        val maxTimeoutSeconds: Int
    ) {
        fun supports(tool: VerifierTool): Boolean =
            tools.isEmpty() || tools.contains(tool.wireName) || tools.contains(tool.name.lowercase())
    }

    /** A base URL the phone is willing to sign and send to. Loopback and private hosts may use http. */
    fun validUrl(raw: String): Boolean {
        val url = raw.trim().trimEnd('/')
        if (url.length !in 10..160) return false
        if (!URL_PATTERN.matches(url)) return false
        if (url.contains("@") || url.contains("?") || url.contains("#")) return false
        return url.startsWith("https://") || isLocal(url)
    }

    fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')

    fun isLocal(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
    }

    /**
     * The job body. Structured fields only: the vault builds its own command line, which is why a
     * caller can never smuggle shell syntax through this interface even if the planner regressed.
     */
    fun jobBody(job: LeanJobSpec): String {
        val payload = linkedMapOf<String, Any?>(
            "module" to job.module,
            "declarations" to job.declarations
        )
        if (job.source != null) payload["source_b64"] = JobPlanner.encode(job.source)
        return JsonLite.stringify(
            linkedMapOf<String, Any?>(
                "contract" to VERSION,
                "job" to job.id,
                "tool" to job.tool.wireName,
                "timeout_seconds" to job.timeoutSeconds,
                "project" to linkedMapOf<String, Any?>(
                    "id" to job.project.id,
                    "root" to job.project.root,
                    "module" to job.project.defaultModule,
                    "toolchain" to job.project.toolchain
                ),
                "payload" to payload
            )
        )
    }

    fun headers(job: LeanJobSpec, secret: String, timestampMs: Long): Map<String, String> {
        val body = jobBody(job)
        return linkedMapOf(
            "Content-Type" to "application/json",
            HEADER_CONTRACT to VERSION.toString(),
            HEADER_JOB to job.id,
            HEADER_TIMESTAMP to timestampMs.toString(),
            HEADER_SIGNATURE to sign(secret, timestampMs, body)
        )
    }

    fun sign(secret: String, timestampMs: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestampMs\n$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    fun parseHealth(raw: String): Health? {
        val entry = runCatching { JsonLite.parseObject(raw) }.getOrNull() ?: return null
        val contract = entry.int("contract", VERSION)
        if (contract != VERSION) return null
        return Health(
            name = entry.str("name").take(60).ifBlank { "audit-vault" },
            version = entry.str("version").take(40),
            tools = entry.arr("tools").mapNotNull { it?.toString() }.toSet(),
            maxTimeoutSeconds = entry.int("max_timeout_seconds", 1_800).coerceIn(10, 3_600)
        )
    }

    /**
     * Turns the vault's reply into a [JobResult] **the phone believes**, not one the vault asserts.
     *
     * The verdict is recomputed from `exit`, `errors` and the attestation; the vault's own `ok` field is
     * advisory and a disagreement is recorded rather than obeyed. An attestation mismatch, a job-id
     * mismatch, or a contract mismatch marks the result unverified and `ok = false` — a backend that
     * cannot say what it ran does not get to declare a proof correct.
     */
    fun parseJob(raw: String, job: LeanJobSpec, claimedElapsedMs: Long = 0L): JobResult {
        if (raw.length > MAX_BODY_BYTES) {
            return unverified(job, "The vault's reply exceeded ${MAX_BODY_BYTES / 1000} KB.")
        }
        val entry = runCatching { JsonLite.parseObject(raw) }.getOrNull()
            ?: return unverified(job, "The vault's reply was not a JSON object.")

        if (entry.int("contract", VERSION) != VERSION) {
            return unverified(job, "The vault answered a contract this phone does not speak.")
        }
        val echoedJob = entry.str("job").takeIf { it.isNotEmpty() }
        if (echoedJob != null && echoedJob != job.id) {
            return unverified(job, "The vault answered for a different job ($echoedJob).")
        }

        val attestation = entry.obj("attestation") ?: emptyMap()
        val expectedSource = job.source?.let { sha256(it) }
        val claimedSource = attestation.str("source_sha256").takeIf { it.isNotEmpty() }
        val attestationOk = when {
            expectedSource == null -> true
            claimedSource == null -> false
            else -> claimedSource == expectedSource
        }

        val output = entry.arr("output")
            .mapNotNull { it?.toString()?.take(2_000) }
            .takeLast(MAX_OUTPUT_LINES)
        val errors = entry.arr("errors")
            .mapNotNull { it?.toString()?.take(2_000) }
            .take(100)
            .toMutableList()

        if (!attestationOk) {
            errors += "attestation mismatch: the vault did not run the bytes this phone sent"
        }
        val exit = entry.int("exit", if (errors.isEmpty()) 0 else 1)
        val ok = exit == 0 && errors.isEmpty()
        val claimedOk = entry.bool("ok", ok)
        if (claimedOk != ok) {
            errors += "the vault claimed ok=$claimedOk but its own exit and errors say otherwise"
        }

        return JobResult(
            ok = ok,
            exitCode = exit,
            output = output,
            errors = errors.take(100),
            sorryCount = entry.int("sorry_count").coerceIn(0, 1_000_000),
            axioms = entry.arr("axioms").mapNotNull { it?.toString()?.take(500) }.take(50),
            elapsedMs = (entry.long("elapsed_ms", claimedElapsedMs)).coerceIn(0L, 3_600_000L),
            transport = if (attestationOk) "audit-vault" else "audit-vault-unverified"
        )
    }

    private fun unverified(job: LeanJobSpec, reason: String): JobResult = JobResult(
        ok = false,
        exitCode = -1,
        output = listOf("[VERIFIER] $reason"),
        errors = listOf(reason),
        transport = "none"
    )

    private val URL_PATTERN = Regex("^https?://[A-Za-z0-9._\\[\\]:-]{3,120}(?:/[A-Za-z0-9._~/-]{0,80})?$")
}

/** A minimal transport so the executor can be driven by a fake in the harness and by HTTP on a device. */
interface VaultTransport {
    data class Reply(val status: Int, val body: String)

    fun get(path: String): Reply
    fun post(path: String, headers: Map<String, String>, body: String): Reply
}

/**
 * HTTP transport. Redirects are **not** followed: a 302 pointing somewhere else would carry the
 * signature and the payload to a host this phone never chose.
 */
class HttpVaultTransport(
    private val baseUrl: String,
    private val timeoutMs: Int = 20_000
) : VaultTransport {

    override fun get(path: String): VaultTransport.Reply = call("GET", path, emptyMap(), null)

    override fun post(path: String, headers: Map<String, String>, body: String): VaultTransport.Reply =
        call("POST", path, headers, body)

    private fun call(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String?
    ): VaultTransport.Reply {
        val target = AuditVaultContract.normalizeUrl(baseUrl) + path
        // A blocked call is an ordinary transport failure here: status 0, honest body, no throw, so
        // the executor's existing fallback decides what to do instead of an exception escaping.
        val clearance = NetworkGuard.authorize(target, userInitiated = true)
        if (clearance.blocked) {
            return VaultTransport.Reply(0, NetworkGuard.refusalMessage(clearance, "the audit vault call"))
        }
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return VaultTransport.Reply(status, text.take(AuditVaultContract.MAX_BODY_BYTES))
    }
}

/**
 * A job executor backed by an audit-vault deployment.
 *
 * Every failure mode that means "the vault did not answer for this job" — unreachable, non-200, bad
 * contract, oversized reply — comes back as `transport = "none"` so [ExecutorRouter] can fall back to
 * the on-device SSH path instead of pretending a proof was checked.
 */
class AuditVaultExecutor(
    private val transport: VaultTransport,
    private val secret: String,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : JobExecutor {

    override fun execute(job: LeanJobSpec): JobResult {
        val body = AuditVaultContract.jobBody(job)
        if (body.length > AuditVaultContract.MAX_BODY_BYTES) {
            return unavailable("The planned job exceeded the vault's ${AuditVaultContract.MAX_BODY_BYTES / 1000} KB request limit.")
        }
        val headers = AuditVaultContract.headers(job, secret, clock())
        val reply = runCatching {
            transport.post(AuditVaultContract.PATH_JOBS, headers, body)
        }.getOrElse { error ->
            return unavailable("audit-vault is unreachable: ${error.message ?: error.javaClass.simpleName}")
        }
        if (reply.status != 200) {
            return unavailable("audit-vault answered ${reply.status}.")
        }
        return AuditVaultContract.parseJob(reply.body, job)
    }

    private fun unavailable(reason: String): JobResult = JobResult(
        ok = false,
        exitCode = -1,
        output = listOf("[VERIFIER] $reason"),
        errors = listOf(reason),
        transport = "none"
    )
}

/**
 * Chooses a backend per job, and never silently swaps one verdict for another.
 *
 * - A **verdict** (`ok = false` because the proof failed) is never retried elsewhere; it is the answer.
 * - `transport = "none"` means the backend did not answer for this job, so the fallback runs instead and
 *   the result carries a line saying why the primary was skipped.
 * - With no fallback configured the job fails honestly rather than reporting a proof nobody checked.
 */
class ExecutorRouter(
    private val primary: JobExecutor?,
    private val fallback: JobExecutor?,
    private val primaryLabel: String = "audit-vault",
    private val fallbackLabel: String = "the Debian VM",
    private val primaryAvailable: () -> Boolean = { true }
) : JobExecutor {

    override fun execute(job: LeanJobSpec): JobResult {
        var skipped: String? = null
        val backend = primary
        if (backend != null) {
            if (!primaryAvailable()) {
                skipped = "$primaryLabel is not healthy"
            } else {
                val result = runCatching { backend.execute(job) }.getOrNull()
                if (result != null && result.transport != "none") return result
                skipped = result?.errors?.firstOrNull()
                    ?: result?.output?.firstOrNull()
                    ?: "$primaryLabel did not answer"
            }
        }
        val secondary = fallback
            ?: return JobResult(
                ok = false,
                exitCode = -1,
                output = listOf("[VERIFIER] ${skipped ?: "No backend is configured."} No fallback is configured."),
                errors = listOfNotNull(skipped),
                transport = "none"
            )
        val result = secondary.execute(job)
        return result.copy(
            output = listOf("[VERIFIER] using $fallbackLabel: ${skipped ?: "primary skipped"}") + result.output,
            transport = if (result.transport == "none") "none" else result.transport
        )
    }
}

/**
 * Caches a `/v1/health` probe so a dead vault costs one timeout per window rather than one per job.
 * The phone keeps working when the vault is down; it just stops paying the latency to find out.
 */
class HealthProbe(
    private val transport: VaultTransport,
    private val ttlMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var checkedAt: Long = Long.MIN_VALUE

    @Volatile
    private var healthy: Boolean = false

    @Volatile
    private var last: AuditVaultContract.Health? = null

    val latest: AuditVaultContract.Health? get() = last

    fun available(): Boolean {
        val now = clock()
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < ttlMs) return healthy
        val reply = runCatching { transport.get(AuditVaultContract.PATH_HEALTH) }.getOrNull()
        healthy = reply != null && reply.status == 200 &&
            AuditVaultContract.parseHealth(reply.body)?.let { last = it; true } == true
        checkedAt = now
        return healthy
    }

    fun invalidate() {
        checkedAt = Long.MIN_VALUE
    }

    fun describe(): String {
        if (!available()) return "down"
        val health = last ?: return "up"
        val toolCount = health.tools.size
        return "up · ${health.name} ${health.version}".trim() +
            if (toolCount > 0) " · $toolCount tools" else ""
    }
}
