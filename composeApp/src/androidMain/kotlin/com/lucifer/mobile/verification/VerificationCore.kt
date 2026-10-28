package com.lucifer.mobile.verification

import com.lucifer.mobile.resilience.JsonLite
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Lean verification server, in pure Kotlin: authentication, metering, source screening, the MCP
 * wire protocol, and the exact remote command each job becomes.
 *
 * The phone never runs Lean. Compiling mathlib-class projects needs tens of gigabytes of RAM and
 * hours of CPU; a work profile on a Pixel has neither. So Lucifer's world holds the *control plane* —
 * the MCP endpoint, the keys, the quota meter, the job ledger, the tunnel — and every actual
 * elaboration happens on a remote Lean host (a cloud VM, a container behind Cloudflare, or the
 * already-configured Debian VM). That split is the design, not a compromise to explain away.
 *
 * Nothing here touches Android, so the harness can prove the parts where a mistake is expensive:
 * who is allowed to call what, what a job is allowed to contain, and what a client is charged.
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Tools
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The complete surface the server exposes. There is no "run this Lean" tool and no shell tool: a
 * client can ask for one of these five things against a configured project, and nothing else.
 */
enum class VerifierTool(
    val wireName: String,
    val credits: Int,
    val timeoutSeconds: Int,
    val needsSource: Boolean,
    val summary: String
) {
    CHECK(
        "lean.check", 1, 120, true,
        "Type-check one Lean snippet or declaration against a project's dependencies; returns errors, sorries and axioms."
    ),
    BUILD(
        "lean.build", 5, 900, false,
        "Run `lake build` for one module of a project; returns the log tail and failure count."
    ),
    VERIFY(
        "lean.verify", 8, 1800, false,
        "Full verification of a project (or module) with an axiom audit and a machine-readable verdict."
    ),
    SORRY_SCAN(
        "lean.sorry_scan", 2, 300, false,
        "Count `sorry`, `admit` and `axiom` declarations in a project so a proof cannot pass by cheating."
    ),
    AXIOMS(
        "lean.axioms", 3, 300, false,
        "Print the axioms each named theorem depends on (`#print axioms`), the check a reviewer actually asks for."
    ),
    GATES(
        "lean.gates", 6, 300, false,
        "The four audit-vault gates over a submission: no sorry/admit/native_decide/axiom, no vacuous or " +
            "degenerate statement, no name that asserts more than it proves, and declaration parity. " +
            "Reports suspects for a human; it never certifies."
    );

    val idempotent: Boolean get() = this == CHECK || this == SORRY_SCAN || this == AXIOMS
}

enum class Scope { VERIFY, BUILD, ADMIN, READ }

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Projects — configured locally, never supplied by a client
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A project the server is willing to touch. The root path is configuration, not input: a caller can
 * name a project by id, and only projects in this list exist as far as the server is concerned.
 */
data class LeanProject(
    val id: String,
    val label: String,
    val root: String,
    val defaultModule: String? = null,
    val toolchain: String? = null,
    val publicOk: Boolean = true
) {
    companion object {
        private val idPattern = Regex("^[a-z][a-z0-9_-]{1,31}$")
        private val rootPattern = Regex("^/[A-Za-z0-9_./-]{2,200}$")
        private val modulePattern = Regex("^(?:[A-Za-z0-9_]+)(?:\\.[A-Za-z0-9_]+){0,8}$")
    
        private val declarationPattern = Regex("^[A-Za-z_][A-Za-z0-9_'.]{0,120}$")

        fun validId(id: String): Boolean = idPattern.matches(id)
        fun validRoot(root: String): Boolean = rootPattern.matches(root) && !root.contains("..")
        fun validModule(module: String): Boolean = modulePattern.matches(module) && module.length <= 120
        fun validDeclaration(name: String): Boolean = declarationPattern.matches(name)
    }
}

object ProjectRegistry {

    /** Parsed from local configuration; a malformed entry is dropped rather than guessed at. */
    fun parse(entries: List<Map<String, Any?>>): List<LeanProject> = entries.mapNotNull { entry ->
        val id = (entry["id"] as? String)?.trim().orEmpty()
        val root = (entry["root"] as? String)?.trim().orEmpty()
        if (!LeanProject.validId(id) || !LeanProject.validRoot(root)) return@mapNotNull null
        LeanProject(
            id = id,
            label = (entry["label"] as? String)?.take(60) ?: id,
            root = root,
            defaultModule = (entry["module"] as? String)?.takeIf { LeanProject.validModule(it) },
            toolchain = (entry["toolchain"] as? String)?.take(60),
            publicOk = entry["public"] as? Boolean ?: true
        )
    }.distinctBy { it.id }

    fun find(projects: List<LeanProject>, id: String): LeanProject? = projects.firstOrNull { it.id == id }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Source screening
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A caller may submit Lean source for `lean.check`. Lean source is code: `#eval`, `run_cmd` and
 * `run_tac` execute during elaboration, `unsafe` and `@[extern]` escape the logic, and a compiled
 * `import` can pull in a plugin. None of that is needed to check a theorem, so it is rejected at the
 * door — with the line number, so a legitimate author can see what tripped it.
 *
 * This is *defence in depth*, not the sandbox. The remote executor is still expected to run
 * unprivileged, without network, with a memory cap and a wall clock, because a screen that only looks
 * at text can always be argued with. Both are required.
 */
object SourceGuard {

    const val MAX_SOURCE_CHARS = 120_000
    const val MAX_LINES = 4_000

    data class Verdict(val ok: Boolean, val reason: String? = null, val line: Int? = null) {
        companion object {
            val Fine = Verdict(true)
        }
    }

    /** Constructs that mean "run something", "bypass the kernel", or "load a native plugin". */
    private val forbidden = listOf(
        "#eval" to "runs code at elaboration time",
        "run_cmd" to "runs code at elaboration time",
        "run_tac" to "runs tactics at elaboration time",
        "run_elab" to "runs code at elaboration time",
        "@[extern" to "links native code",
        "@[implemented_by" to "replaces a definition with native code",
        "unsafe" to "escapes the kernel's guarantees",
        "attribute [extern" to "links native code",
        "initialize " to "runs an initializer",
        "builtin_initialize " to "runs an initializer",
        "IO.FS" to "touches the filesystem",
        "IO.Process" to "starts a process",
        "System.FilePath" to "touches the filesystem"
    )

    fun inspect(source: String): Verdict {
        if (source.isBlank()) return Verdict(false, "Source is empty.")
        if (source.length > MAX_SOURCE_CHARS) {
            return Verdict(false, "Source is ${source.length} characters; the limit is $MAX_SOURCE_CHARS.")
        }
        if (source.count { it == '\n' } > MAX_LINES) return Verdict(false, "Source exceeds $MAX_LINES lines.")
        if (source.contains('\u0000')) return Verdict(false, "Source contains a NUL byte.")

        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore("--")     // a comment cannot execute
            for ((needle, why) in forbidden) {
                if (line.contains(needle)) {
                    return Verdict(false, "Line ${index + 1} uses `$needle`, which $why. lean.check accepts proof text only.", index + 1)
                }
            }
            val imports = Regex("^\\s*import\\s+([A-Za-z0-9_.]+)").find(line)
            if (imports != null) {
                val module = imports.groupValues[1]
                if (!LeanProject.validModule(module)) {
                    return Verdict(false, "Line ${index + 1} imports `$module`, which is not a valid module name.", index + 1)
                }
                if (module.startsWith("Lean.") || module.startsWith("Lake.")) {
                    return Verdict(
                        false,
                        "Line ${index + 1} imports `$module`; the project's own dependencies are already available and tooling modules are not exposed.",
                        index + 1
                    )
                }
            }
        }
        return Verdict.Fine
    }

    /**
     * Cheap, honest count used by `lean.sorry_scan` when the remote host cannot be reached.
     *
     * The vault's gate 1 is *zero* sorry/admit/native_decide/axiom, so all four are counted. Counting
     * only `sorry` and `admit` would report a clean project that proves its theorems by trusting the
     * compiler.
     */
    fun countPlaceholders(source: String): Int {
        val withoutComments = StatementGates.stripComments(source)
        return Regex("\\b(sorry|admit|native_decide|sorryAx)\\b").findAll(withoutComments).count() +
            Regex("^\\s*(?:private\\s+)?axiom\\s+", RegexOption.MULTILINE).findAll(withoutComments).count()
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Keys
// ─────────────────────────────────────────────────────────────────────────────────────────────

enum class Plan(val label: String, val creditsPerPeriod: Int, val usdPerCredit: Double) {
    INTERNAL("Lucifer · internal", Int.MAX_VALUE, 0.0),
    FREE("Free", 50, 0.0),
    METERED("Metered", 5_000, 0.012),
    ENTERPRISE("Enterprise", 200_000, 0.008)
}

data class ApiKeyRecord(
    val id: String,
    val prefix: String,
    val hash: String,
    val plan: Plan,
    val scopes: Set<Scope>,
    val label: String,
    val createdAt: Long,
    val revoked: Boolean = false
) {
    fun allows(scope: Scope): Boolean = !revoked && (scopes.contains(Scope.ADMIN) || scopes.contains(scope))

    fun publicView(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "prefix" to prefix,
        "plan" to plan.name,
        "scopes" to scopes.map { it.name }.sorted(),
        "label" to label,
        "createdAt" to createdAt,
        "revoked" to revoked
    )
}

/**
 * Bearer keys look like `lvs_<id>_<secret>`. Only the SHA-256 of the whole token is stored, so a
 * leaked vault file cannot be replayed, and the id lets the server find the record without comparing
 * every secret.
 */
object ApiKeys {

    const val PREFIX = "lvs"
    private const val SECRET_BYTES = 24

    data class Issued(val record: ApiKeyRecord, val token: String)

    fun issue(
        label: String,
        plan: Plan,
        scopes: Set<Scope>,
        now: Long,
        random: SecureRandom = SecureRandom()
    ): Issued {
        val id = "k" + (1..8).map { HEX[random.nextInt(HEX.length)] }.joinToString("")
        val secret = (1..SECRET_BYTES).map { HEX[random.nextInt(HEX.length)] }.joinToString("")
        val token = "$PREFIX" + "_" + id + "_" + secret
        val record = ApiKeyRecord(
            id = id,
            prefix = "$PREFIX" + "_" + id,
            hash = hash(token),
            plan = plan,
            scopes = if (scopes.isEmpty()) setOf(Scope.VERIFY) else scopes,
            label = label.trim().take(60).ifBlank { "key $id" },
            createdAt = now
        )
        return Issued(record, token)
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    /** Extracts the bearer token from an `Authorization` header, tolerating case and whitespace. */
    fun tokenFromHeader(header: String?): String? {
        val value = header?.trim() ?: return null
        val parts = value.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return null
        if (!parts[0].equals("Bearer", ignoreCase = true)) return null
        val token = parts[1].trim()
        if (token.length !in 20..200) return null
        if (!token.startsWith("$PREFIX" + "_")) return null
        return token
    }

    /** Constant-time comparison of the presented token against the stored hash. */
    fun verify(record: ApiKeyRecord, token: String): Boolean {
        if (record.revoked) return false
        val presented = hash(token)
        if (presented.length != record.hash.length) return false
        var difference = 0
        for (index in presented.indices) {
            difference = difference or (presented[index].code xor record.hash[index].code)
        }
        return difference == 0
    }

    fun find(records: List<ApiKeyRecord>, token: String): ApiKeyRecord? {
        val id = token.split("_").getOrNull(1) ?: return null
        val record = records.firstOrNull { it.id == id } ?: return null
        return if (verify(record, token)) record else null
    }

    private const val HEX = "abcdefghijklmnopqrstuvwxyz0123456789"
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Metering and money
// ─────────────────────────────────────────────────────────────────────────────────────────────

data class MeterState(
    val keyId: String,
    val plan: Plan,
    val usedCredits: Int = 0,
    val periodStartEpochMs: Long = 0L,
    val lifetimeCredits: Int = 0
) {
    val remaining: Int
        get() = if (plan.creditsPerPeriod == Int.MAX_VALUE) Int.MAX_VALUE
        else (plan.creditsPerPeriod - usedCredits).coerceAtLeast(0)

    fun view(): Map<String, Any?> = linkedMapOf(
        "keyId" to keyId,
        "plan" to plan.name,
        "used" to usedCredits,
        "limit" to (if (plan.creditsPerPeriod == Int.MAX_VALUE) null else plan.creditsPerPeriod),
        "remaining" to (if (remaining == Int.MAX_VALUE) null else remaining),
        "lifetime" to lifetimeCredits,
        "usdPerCredit" to plan.usdPerCredit
    )
}

/**
 * Credits, not dollars, are the unit the server enforces. A credit is a rough CPU-second of remote
 * elaboration; each tool costs what it usually takes. `INTERNAL` is Lucifer's own key and is never
 * blocked, because he is the machine that does the work.
 *
 * No payment processor is wired. The server *enforces* quota — it refuses work it cannot bill for —
 * and the ledger records what a metered key consumed so a real processor can be attached later
 * without renegotiating semantics.
 */
object Billing {

    const val PERIOD_MILLIS = 30L * 24L * 60L * 60L * 1000L

    data class Decision(val allowed: Boolean, val charge: Int, val remainingAfter: Int, val code: Int?, val reason: String) {
        val refused: Boolean get() = !allowed
    }

    fun chargeFor(tool: VerifierTool, quantity: Int = 1): Int = tool.credits * quantity.coerceIn(1, 8)

    /** Rolls the meter into a fresh period without losing the lifetime total. */
    fun roll(meter: MeterState, now: Long): MeterState =
        if (now - meter.periodStartEpochMs >= PERIOD_MILLIS) {
            meter.copy(usedCredits = 0, periodStartEpochMs = now)
        } else {
            meter
        }

    fun authorize(meter: MeterState, tool: VerifierTool, quantity: Int = 1): Decision {
        val charge = chargeFor(tool, quantity)
        if (meter.plan == Plan.INTERNAL) {
            return Decision(true, 0, Int.MAX_VALUE, null, "Internal key: no charge.")
        }
        val remaining = meter.remaining
        if (charge > remaining) {
            return Decision(
                allowed = false,
                charge = charge,
                remainingAfter = remaining,
                code = McpProtocol.QUOTA_EXCEEDED,
                reason = "This key has $remaining credits left and ${tool.wireName} costs $charge. " +
                    "Top up or use a plan with a higher ceiling."
            )
        }
        return Decision(true, charge, remaining - charge, null, "${tool.wireName} costs $charge credits.")
    }

    fun apply(meter: MeterState, charge: Int): MeterState =
        meter.copy(usedCredits = meter.usedCredits + charge, lifetimeCredits = meter.lifetimeCredits + charge)

    fun usdFor(credits: Int, plan: Plan): Double = credits * plan.usdPerCredit
}

/** One line of what a key consumed, kept for invoices and for Lucifer's own accounting. */
data class UsageLine(
    val at: Long,
    val keyId: String,
    val tool: VerifierTool,
    val credits: Int,
    val usd: Double,
    val ok: Boolean
)

object MeteringLedger {
    const val MAX_LINES = 500

    private val lines = ArrayDeque<UsageLine>()

    fun record(line: UsageLine): List<UsageLine> {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        return snapshot()
    }

    fun snapshot(): List<UsageLine> = lines.toList()

    fun clear() = lines.clear()

    fun totalCredits(afterEpochMs: Long = 0L): Int = lines.filter { it.at >= afterEpochMs }.sumOf { it.credits }

    fun totalUsd(afterEpochMs: Long = 0L): Double = lines.filter { it.at >= afterEpochMs }.sumOf { it.usd }

    fun byKey(afterEpochMs: Long = 0L): Map<String, Int> =
        lines.filter { it.at >= afterEpochMs }.groupBy { it.keyId }.mapValues { entry -> entry.value.sumOf { it.credits } }

    fun summary(afterEpochMs: Long = 0L): String {
        val credits = totalCredits(afterEpochMs)
        val usd = totalUsd(afterEpochMs)
        if (credits == 0) return "No verification work metered yet."
        return "$credits credits metered · $${"%.2f".format(usd)} billable · ${snapshot().size} lines"
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Jobs
// ─────────────────────────────────────────────────────────────────────────────────────────────

data class LeanJobSpec(
    val id: String,
    val tool: VerifierTool,
    val project: LeanProject,
    val module: String?,
    val declarations: List<String>,
    val source: String?,
    val timeoutSeconds: Int,
    val keyId: String,
    val issuedAt: Long,
    val charge: Int
)

data class JobResult(
    val ok: Boolean,
    val exitCode: Int,
    val output: List<String>,
    val errors: List<String> = emptyList(),
    val sorryCount: Int = 0,
    val axioms: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
    val transport: String = "unknown",
    /** Declarations the host actually found, parsed from `LUCIFER_PARITY` markers. */
    val parity: List<String> = emptyList()
)

/**
 * Turns one validated MCP call into the exact shell command that the remote Lean host will run.
 *
 * Source travels base64-encoded so quoting can never break out, the file lands in a fresh `mktemp -d`,
 * and the whole thing sits under `timeout`. The exit code is echoed with a marker because the output
 * has to survive `tail`.
 */
object JobPlanner {

    private const val MARKER = "LUCIFER_EXIT"
    private const val OUTPUT_TAIL_LINES = 240

    sealed class Planned {
        data class Ready(val job: LeanJobSpec) : Planned()
        data class Rejected(val reason: String) : Planned()
    }

    fun plan(
        tool: VerifierTool,
        params: Map<String, Any?>,
        projects: List<LeanProject>,
        keyId: String,
        now: Long,
        charge: Int,
        idFactory: () -> String
    ): Planned {
        val projectId = (params["project"] as? String)?.trim().orEmpty()
        if (!LeanProject.validId(projectId)) return Planned.Rejected("`project` must be a configured project id.")
        val project = ProjectRegistry.find(projects, projectId)
            ?: return Planned.Rejected("No project `$projectId` is configured on this server.")

        val moduleParam = (params["module"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val module = moduleParam ?: project.defaultModule
        if (module != null && !LeanProject.validModule(module)) {
            return Planned.Rejected("`module` must be a dotted Lean module name.")
        }
        if ((tool == VerifierTool.BUILD || tool == VerifierTool.VERIFY) && module == null) {
            return Planned.Rejected("${tool.wireName} needs a `module` (or a project with a default module).")
        }

        val declarations = (params["declarations"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf { name -> LeanProject.validDeclaration(name) } }
            ?: emptyList()
        if (tool == VerifierTool.AXIOMS && declarations.isEmpty()) {
            return Planned.Rejected("lean.axioms needs at least one theorem name in `declarations`.")
        }
        if (declarations.size > 24) return Planned.Rejected("At most 24 declarations per call.")

        val source = (params["source"] as? String)?.let { raw ->
            if (tool != VerifierTool.CHECK && tool != VerifierTool.GATES) {
                return Planned.Rejected("Only lean.check and lean.gates accept `source`; other tools act on the configured project.")
            }
            val verdict = SourceGuard.inspect(raw)
            if (!verdict.ok) return Planned.Rejected(verdict.reason ?: "Source rejected.")
            raw
        }
        if (tool.needsSource && source == null) return Planned.Rejected("lean.check needs `source`.")
        if (tool == VerifierTool.GATES && source == null && declarations.isEmpty()) {
            return Planned.Rejected("lean.gates needs `source` (the statement to judge), `declarations` (names to verify), or both.")
        }

        val requestedTimeout = (params["timeout_seconds"] as? Number)?.toInt() ?: tool.timeoutSeconds
        val timeout = requestedTimeout.coerceIn(10, tool.timeoutSeconds)

        return Planned.Ready(
            LeanJobSpec(
                id = idFactory(),
                tool = tool,
                project = project,
                module = module,
                declarations = declarations,
                source = source,
                timeoutSeconds = timeout,
                keyId = keyId,
                issuedAt = now,
                charge = charge
            )
        )
    }

    /** The command the remote host runs. Pure, so the harness can pin it exactly. */
    fun remoteCommand(job: LeanJobSpec): String {
        val root = job.project.root
        val lines = mutableListOf("set -u")
        lines += "ROOT='$root'"
        lines += "if [ ! -d \"\$ROOT\" ]; then echo '[VERIFIER] project root missing'; echo '$MARKER=127'; exit 0; fi"
        lines += "D=\$(mktemp -d /tmp/lucifer-verify.XXXXXX) || exit 1"
        lines += "trap 'rm -rf \"\$D\"' EXIT"
        lines += "cd \"\$ROOT\" || { echo '[VERIFIER] cannot enter project'; echo '$MARKER=126'; exit 0; }"

        when (job.tool) {
            VerifierTool.CHECK -> {
                val payload = encode(job.source.orEmpty())
                lines += "printf '%s' '$payload' | base64 -d > \"\$D/Check.lean\""
                val imports = job.module?.let { "import $it\n" } ?: ""
                if (imports.isNotEmpty()) {
                    lines += "printf '%s' '${encode(imports)}' | base64 -d > \"\$D/Imports.lean\""
                    lines += "cat \"\$D/Imports.lean\" \"\$D/Check.lean\" > \"\$D/Joined.lean\""
                } else {
                    lines += "mv \"\$D/Check.lean\" \"\$D/Joined.lean\""
                }
                lines += runCapture("lake env lean \"\$D/Joined.lean\"", job.timeoutSeconds)
            }
            VerifierTool.BUILD -> {
                val module = job.module.orEmpty()
                lines += runCapture("lake build $module", job.timeoutSeconds)
            }
            VerifierTool.VERIFY -> {
                val module = job.module
                val buildStep = if (module != null) "lake build $module" else "lake build"
                lines += runCapture(buildStep, job.timeoutSeconds)
            }
            VerifierTool.SORRY_SCAN -> {
                lines += "OUT=\$(grep -rn -E '(^|[^A-Za-z0-9_])sorry([^A-Za-z0-9_]|$)|\\\\badmit\\\\b' --include='*.lean' . 2>/dev/null | head -n 200 || true)"
                lines += "COUNT=\$(printf '%s' \"\$OUT\" | grep -c . || true)"
                lines += "printf '%s\\n' \"\$OUT\""
                lines += "echo \"LUCIFER_SORRY=\$COUNT\""
                lines += "echo '$MARKER=0'"
            }
            VerifierTool.GATES -> {
                lines += "G1=\$(grep -rnE '(^|[^A-Za-z0-9_])(sorry|admit|native_decide|sorryAx)([^A-Za-z0-9_]|$)|^[[:space:]]*(private[[:space:]]+)?axiom[[:space:]]' --include='*.lean' . 2>/dev/null | head -n 200 || true)"
                lines += "printf '%s\\n' \"\$G1\""
                lines += "echo \"LUCIFER_SORRY=\$(printf '%s' \"\$G1\" | grep -c . || true)\""
                job.declarations.forEach { name ->
                    lines += "if grep -rqE \"(^|[^A-Za-z0-9_.'])(theorem|lemma|def|abbrev)[[:space:]]+$name([^A-Za-z0-9_'.]|$)\" --include='*.lean' . 2>/dev/null; then echo 'LUCIFER_PARITY=$name:1'; else echo 'LUCIFER_PARITY=$name:0'; fi"
                }
                lines += "echo '$MARKER=0'"
            }
            VerifierTool.AXIOMS -> {
                val theorems = job.declarations.joinToString("\n") { "  #print axioms $it" }
                val source = "import ${job.module ?: job.project.defaultModule ?: "Init"}\nopen scoped Classical\n$theorems\n"
                lines += "printf '%s' '${encode(source)}' | base64 -d > \"\$D/Axioms.lean\""
                lines += runCapture("lake env lean \"\$D/Axioms.lean\"", job.timeoutSeconds)
            }
        }
        lines += "exit 0"
        return lines.joinToString("\n")
    }

    private fun runCapture(command: String, timeoutSeconds: Int): List<String> = listOf(
        "set +e",
        "OUT=\$(timeout $timeoutSeconds $command 2>&1)",
        "RC=\$?",
        "set -e",
        "printf '%s\\n' \"\$OUT\" | tail -n $OUTPUT_TAIL_LINES",
        "echo '$MARKER=\$RC'"
    )

    /** Base64 without a dependency: Android's android.util.Base64 is not available in pure Kotlin. */
    fun encode(text: String): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            builder.append(table[first shr 2])
            builder.append(table[((first and 0x03) shl 4) or (second shr 4)])
            builder.append(if (index + 1 < bytes.size) table[((second and 0x0F) shl 2) or (third shr 6)] else '=')
            builder.append(if (index + 2 < bytes.size) table[third and 0x3F] else '=')
            index += 3
        }
        return builder.toString()
    }
}

/** Reads the markers a remote host prints, so a log tail becomes a verdict. */
object ResultParser {

    fun parse(output: List<String>, transport: String, elapsedMs: Long): JobResult {
        val exit = output.firstNotNullOfOrNull { line ->
            Regex("LUCIFER_EXIT=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 1
        val sorryCount = output.firstNotNullOfOrNull { line ->
            Regex("LUCIFER_SORRY=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: output.count { it.contains("declaration uses 'sorry'") }
        val errors = output.filter { it.trimStart().startsWith("error:") || it.contains(" error: ") }.take(40)
        val axioms = output
            .mapNotNull { line ->
                Regex("'?([A-Za-z0-9_.']+)'? depends on axioms: \\[(.*)]").find(line)?.let {
                    "${it.groupValues[1]} ← ${it.groupValues[2].ifBlank { "nothing (proved)" }}"
                }
            }
            .take(40)
        val parity = output
            .mapNotNull { line ->
                Regex("LUCIFER_PARITY=([A-Za-z0-9_'.]+):1").find(line)?.groupValues?.get(1)
            }
            .distinct()
        val clean = output.filterNot { it.contains("LUCIFER_EXIT=") || it.contains("LUCIFER_PARITY=") }
        return JobResult(
            ok = exit == 0 && errors.isEmpty(),
            exitCode = exit,
            output = clean.takeLast(240),
            errors = errors,
            sorryCount = sorryCount,
            axioms = axioms,
            elapsedMs = elapsedMs,
            transport = transport,
            parity = parity
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// MCP
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Anything that can carry a job to a remote Lean host. Implemented by the SSH and HTTP transports. */
interface JobExecutor {
    fun execute(job: LeanJobSpec): JobResult
}

/**
 * JSON-RPC 2.0 over the Model Context Protocol, enough of it to be a real server:
 * `initialize`, `ping`, `tools/list`, `tools/call`.
 *
 * `tools/call` is the only method that spends money or reaches a remote machine, and it goes through
 * four gates in order: key and scope, tool allowlist, parameter planning (which includes the source
 * screen), then quota. The executor is the last thing touched.
 */
object McpProtocol {

    const val VERSION = "2024-11-05"
    const val SERVER_NAME = "lucifer-lean-verifier"

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val UNAUTHORIZED = -32001
    const val QUOTA_EXCEEDED = -32002
    const val REFUSED = -32003

    data class Caller(val key: ApiKeyRecord, val meter: MeterState)

    data class Handling(
        val body: String,
        val status: Int = 200,
        val job: LeanJobSpec? = null,
        val charge: Int = 0,
        val result: JobResult? = null,
        val gates: StatementGates.Report? = null
    )

    fun tools(): List<Map<String, Any?>> = VerifierTool.entries.map { tool ->
        linkedMapOf(
            "name" to tool.wireName,
            "description" to tool.summary,
            "inputSchema" to linkedMapOf<String, Any?>(
                "type" to "object",
                "properties" to linkedMapOf<String, Any?>(
                    "project" to linkedMapOf(
                        "type" to "string",
                        "description" to "Configured project id on this server."
                    ),
                    "module" to linkedMapOf(
                        "type" to "string",
                        "description" to "Dotted Lean module name, e.g. ToE.Physics.Spacetime."
                    ),
                    "source" to linkedMapOf(
                        "type" to "string",
                        "description" to "Lean source to check (lean.check only). Proof text; no #eval, no unsafe."
                    ),
                    "declarations" to linkedMapOf(
                        "type" to "array",
                        "items" to linkedMapOf("type" to "string"),
                        "description" to "Theorem names for lean.axioms."
                    ),
                    "timeout_seconds" to linkedMapOf(
                        "type" to "integer",
                        "minimum" to 10,
                        "maximum" to tool.timeoutSeconds
                    )
                ),
                "required" to listOf("project"),
                "additionalProperties" to false
            ),
            "annotations" to linkedMapOf(
                "readOnlyHint" to false,
                "destructiveHint" to false,
                "openWorldHint" to false,
                "idempotentHint" to tool.idempotent
            )
        )
    }

    fun handle(
        raw: String,
        caller: Caller?,
        projects: List<LeanProject>,
        now: Long,
        idFactory: () -> String,
        executor: JobExecutor?,
        chargeConsumer: ((Int) -> Unit)? = null
    ): Handling {
        if (raw.length > 400_000) {
            return Handling(error(PARSE_ERROR, "Request exceeds 400 KB."), status = 413)
        }
        val envelope = JsonLite.parseObject(raw)
            ?: return Handling(error(PARSE_ERROR, "Request body is not a JSON object."), status = 400)

        val id = envelope["id"]
        val method = (envelope["method"] as? String)?.trim().orEmpty()
        if (method.isEmpty()) {
            return Handling(error(INVALID_REQUEST, "Missing `method`.", id), status = 400)
        }

        // The handshake is free: a client has to be able to discover the server before it is told a key
        // is required. Everything that costs CPU or money is behind `caller != null`.
        if (method == "initialize") {
            return Handling(
                ok(
                    id,
                    linkedMapOf(
                        "protocolVersion" to VERSION,
                        "serverInfo" to linkedMapOf("name" to SERVER_NAME, "version" to "1.0.0"),
                        "capabilities" to linkedMapOf("tools" to linkedMapOf<String, Any?>())
                    )
                )
            )
        }
        if (method == "ping") return Handling(ok(id, linkedMapOf<String, Any?>()))

        // Everything except the handshake needs a key; the server also checks this before dispatch.
        if (caller == null) {
            return Handling(
                error(UNAUTHORIZED, "Provide a bearer key: Authorization: Bearer lvs_<id>_<secret>", id),
                status = 401
            )
        }

        return when (method) {
            "tools/list" -> {
                if (!caller.key.allows(Scope.READ)) {
                    Handling(error(UNAUTHORIZED, "This key cannot list tools.", id), status = 403)
                } else {
                    Handling(ok(id, linkedMapOf("tools" to tools())))
                }
            }

            "tools/call" -> callTool(id, envelope, caller, projects, now, idFactory, executor, chargeConsumer)

            else -> Handling(error(METHOD_NOT_FOUND, "Unknown method `$method`.", id), status = 404)
        }
    }

    private fun callTool(
        id: Any?,
        envelope: Map<String, Any?>,
        caller: Caller,
        projects: List<LeanProject>,
        now: Long,
        idFactory: () -> String,
        executor: JobExecutor?,
        chargeConsumer: ((Int) -> Unit)?
    ): Handling {
        val params = envelope["params"] as? Map<*, *>
            ?: return Handling(error(INVALID_PARAMS, "`params` must be an object.", id), status = 400)
        val name = (params["name"] as? String)?.trim().orEmpty()
        val tool = VerifierTool.entries.firstOrNull { it.wireName == name }
            ?: return Handling(error(INVALID_PARAMS, "Unknown tool `$name`.", id), status = 404)

        val needsBuildScope = tool == VerifierTool.BUILD || tool == VerifierTool.VERIFY || tool == VerifierTool.SORRY_SCAN
        if (!caller.key.allows(Scope.VERIFY)) {
            return Handling(error(UNAUTHORIZED, "This key cannot run verification.", id), status = 403)
        }
        if (needsBuildScope && !caller.key.allows(Scope.BUILD)) {
            return Handling(error(UNAUTHORIZED, "${tool.wireName} needs the BUILD scope.", id), status = 403)
        }

        val arguments = (params["arguments"] as? Map<*, *>)?.entries
            ?.associate { (key, value) -> key.toString() to value }
            ?: emptyMap()

        // Plan first so a refused request never costs credits and never reaches the executor.
        val planned = JobPlanner.plan(
            tool = tool,
            params = arguments,
            projects = projects,
            keyId = caller.key.id,
            now = now,
            charge = Billing.chargeFor(tool),
            idFactory = idFactory
        )
        val job = when (planned) {
            is JobPlanner.Planned.Rejected -> {
                return Handling(error(REFUSED, planned.reason, id), status = 422)
            }
            is JobPlanner.Planned.Ready -> planned.job
        }

        val decision = Billing.authorize(caller.meter, tool)
        if (decision.refused) {
            return Handling(error(QUOTA_EXCEEDED, decision.reason, id), status = 402)
        }

        val result = executor?.execute(job)
            ?: JobResult(
                ok = false,
                exitCode = -1,
                output = listOf("[VERIFIER] No remote Lean host is configured. The job was planned but not run."),
                transport = "none"
            )
        chargeConsumer?.invoke(decision.charge)

        // Gate 2/3 run here rather than on the host: judging a statement is text analysis, and the
        // customer's source should be judged by the same code no matter which backend elaborated it.
        val gates = if (tool == VerifierTool.GATES) {
            StatementGates.report(
                source = job.source,
                claimed = job.declarations,
                projectPlaceholders = result.sorryCount,
                parityFound = result.parity
            )
        } else {
            null
        }

        val payload = linkedMapOf<String, Any?>(
            "content" to listOf(
                linkedMapOf(
                    "type" to "text",
                    "text" to render(result, job, gates)
                )
            ),
            "isError" to !result.ok,
            "structuredContent" to linkedMapOf<String, Any?>(
                "jobId" to job.id,
                "tool" to job.tool.wireName,
                "project" to job.project.id,
                "ok" to result.ok,
                "exitCode" to result.exitCode,
                "errors" to result.errors,
                "sorryCount" to result.sorryCount,
                "axioms" to result.axioms,
                "elapsedMs" to result.elapsedMs,
                "transport" to result.transport,
                "chargedCredits" to decision.charge
            ).also { structured ->
                gates?.let { structured["gates"] = it.toMap() }
            }
        )
        return Handling(ok(id, payload), job = job, charge = decision.charge, result = result, gates = gates)
    }

    private fun render(result: JobResult, job: LeanJobSpec, gates: StatementGates.Report? = null): String = buildString {
        appendLine(
            "[${job.tool.wireName} · ${job.project.id} · exit ${result.exitCode} · ${result.elapsedMs} ms · ${result.transport}]"
        )
        if (gates != null) {
            appendLine()
            appendLine("gates · ${gates.status} · ${gates.blockers} blocker(s), ${gates.reviews} to review")
            appendLine("finding status · ${gates.findingStatus}")
            gates.suspects.take(40).forEach { appendLine("  ${it.render()}") }
            gates.notes.forEach { appendLine("  note: $it") }
        }
        if (result.errors.isNotEmpty()) {
            appendLine()
            appendLine("errors (${result.errors.size}):")
            result.errors.forEach { appendLine("  $it") }
        }
        if (result.axioms.isNotEmpty()) {
            appendLine()
            appendLine("axioms:")
            result.axioms.forEach { appendLine("  $it") }
        }
        if (result.sorryCount > 0) {
            appendLine()
            appendLine("sorries: ${result.sorryCount}")
        }
        appendLine()
        result.output.takeLast(120).forEach { appendLine(it) }
    }.trim()

    private fun ok(id: Any?, result: Any?): String = JsonLite.stringify(
        linkedMapOf<String, Any?>("jsonrpc" to "2.0", "id" to id, "result" to result)
    )

    private fun error(code: Int, message: String, id: Any? = null): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "id" to id,
            "error" to linkedMapOf<String, Any?>("code" to code, "message" to message)
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Exposure
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * How the loopback endpoint becomes reachable. Lucifer's world hosts the server; Cloudflare (or an
 * SSH reverse tunnel) is what gives it a hostname.
 *
 * The tunnel token is read from a mode-600 file at runtime, never pasted into the command line, so it
 * does not end up in `ps`, in a shell history, or in a log line Lucifer might later quote.
 */
object ExposurePlan {

    const val DEFAULT_PORT = 8791
    const val CONFIG_DIR = "~/.lucifer/verifier"
    const val TOKEN_FILE = "$CONFIG_DIR/tunnel.token"

    data class Exposure(val kind: String, val hostname: String?, val command: String)

    fun cloudflare(hostname: String, port: Int = DEFAULT_PORT): Exposure {
        val host = hostname.trim().lowercase()
        require(hostnamePattern.matches(host)) { "hostname must look like verifier.example.com" }
        val command = listOf(
            "set -eu",
            "mkdir -p $CONFIG_DIR && chmod 700 $CONFIG_DIR",
            "if [ ! -s $TOKEN_FILE ]; then echo '[SETUP] write the Cloudflare tunnel token to $TOKEN_FILE (chmod 600) first.'; exit 2; fi",
            "TUNNEL_TOKEN=\$(cat $TOKEN_FILE)",
            "export TUNNEL_TOKEN",
            "cloudflared tunnel --no-autoupdate --url http://127.0.0.1:$port run 2>&1 | " +
                "grep -E 'Registered tunnel connection|ERR|error' || true",
            "echo '[TUNNEL] cloudflared exited.'"
        ).joinToString("\n")
        return Exposure("cloudflare", host, command)
    }

    fun sshReverse(host: String, user: String = "droid", port: Int = DEFAULT_PORT): Exposure {
        val safeHost = host.trim().takeIf { remoteHostPattern.matches(it) } ?: "127.0.0.1"
        val safeUser = user.trim().takeIf { userNamePattern.matches(it) } ?: "droid"
        return Exposure(
            "ssh-reverse",
            safeHost,
            "ssh -N -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=yes -R 443:127.0.0.1:$port $safeUser@$safeHost"
        )
    }

    fun localOnly(port: Int = DEFAULT_PORT): Exposure = Exposure("loopback", "127.0.0.1:$port", "")

    private val hostnamePattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val remoteHostPattern = Regex("^[A-Za-z0-9.-]{3,120}$")
    private val userNamePattern = Regex("^[a-z_][a-z0-9_-]{0,31}$")
}

/** The one-page description the panel and the docs both use. */
object VerifierCharter {
    fun describe(): String =
        "Lucifer's verifier is an MCP server that checks Lean 4 proofs on a remote host. The phone holds " +
            "the endpoint, the keys, the quota and the record; the elaboration happens elsewhere, because a " +
            "work profile cannot build mathlib. Callers get five tools and no shell. Every call is " +
            "authenticated, scoped, metered, and refused if the key cannot pay for it. Customer source is " +
            "screened for code-execution constructs and still runs unprivileged, without network, under a " +
            "wall clock — both, not either."
}
