// Host-side verification for the Lean verification server's control plane.
//
// The phone never elaborates Lean, so what is worth testing is everything around it: who may call,
// what a job may contain, what it costs, and what the remote command looks like. Those live in pure
// Kotlin and are checked here; the HTTP socket, the SSH transport, the tunnel and the panel need a
// device.
//
// Not part of the Android build. Run with scripts/verify_verification_core.sh.
package com.lucifer.mobile.verification.harness

import com.lucifer.mobile.resilience.JsonLite
import com.lucifer.mobile.verification.ApiKeys
import com.lucifer.mobile.verification.Billing
import com.lucifer.mobile.verification.ExposurePlan
import com.lucifer.mobile.verification.JobExecutor
import com.lucifer.mobile.verification.JobPlanner
import com.lucifer.mobile.verification.JobResult
import com.lucifer.mobile.verification.LeanJobSpec
import com.lucifer.mobile.verification.LeanProject
import com.lucifer.mobile.verification.McpProtocol
import com.lucifer.mobile.verification.MeterState
import com.lucifer.mobile.verification.MeteringLedger
import com.lucifer.mobile.verification.Plan
import com.lucifer.mobile.verification.ProjectRegistry
import com.lucifer.mobile.verification.ResultParser
import com.lucifer.mobile.verification.Scope
import com.lucifer.mobile.verification.SourceGuard
import com.lucifer.mobile.verification.UsageLine
import com.lucifer.mobile.verification.VerifierTool
import java.security.SecureRandom

private var passed = 0
private val failures = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  ok  $name")
    } catch (error: Throwable) {
        failures.add("$name: ${error.message ?: error.javaClass.simpleName}")
        println("FAIL  $name -> ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private val projects = listOf(
    LeanProject("toe", "Omega Theory", "/home/droid/omega", "ToE", "leanprover/lean4:v4.9.0"),
    LeanProject("scratch", "Scratch", "/home/droid/scratch")
)

/** Records what it was asked to run, and returns whatever verdict the test wants. */
private class FakeHost(private val result: JobResult = okResult()) : JobExecutor {
    val jobs = mutableListOf<LeanJobSpec>()
    override fun execute(job: LeanJobSpec): JobResult {
        jobs += job
        return result
    }
}

private fun okResult() = JobResult(
    ok = true,
    exitCode = 0,
    output = listOf("Check.lean: ok"),
    elapsedMs = 42,
    transport = "fake"
)

private var keySeed = 0
private fun key(plan: Plan = Plan.METERED, vararg scopes: Scope): Pair<com.lucifer.mobile.verification.ApiKeyRecord, String> {
    val issued = ApiKeys.issue(
        label = "test ${keySeed++}",
        plan = plan,
        scopes = scopes.toSet(),
        now = 1_000L,
        random = SecureRandom()
    )
    return issued.record to issued.token
}

private fun caller(plan: Plan = Plan.METERED, used: Int = 0, vararg scopes: Scope): McpProtocol.Caller {
    val (record, _) = key(plan, *scopes)
    return McpProtocol.Caller(
        key = record,
        meter = MeterState(record.id, plan, usedCredits = used, periodStartEpochMs = 1_000L)
    )
}

private fun call(
    name: String,
    arguments: String,
    caller: McpProtocol.Caller?,
    executor: JobExecutor?,
    projectList: List<LeanProject> = projects,
    chargeSink: ((Int) -> Unit)? = null
): McpProtocol.Handling {
    val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
    return McpProtocol.handle(body, caller, projectList, 5_000L, { "job-1" }, executor, chargeSink)
}

private fun errorCode(handling: McpProtocol.Handling): Int? {
    val parsed = JsonLite.parseObject(handling.body) ?: return null
    val error = parsed["error"] as? Map<*, *> ?: return null
    return (error["code"] as? Number)?.toInt()
}

fun main() {
    println("Lucifer Lean verification server · control-plane harness")

    // ── MCP handshake ────────────────────────────────────────────────
    test("initialize works without a key, so a client can discover the server") {
        val handling = McpProtocol.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            caller = null,
            projects = projects,
            now = 0L,
            idFactory = { "x" },
            executor = null
        )
        expect(handling.status == 200, "status ${handling.status}")
        val parsed = JsonLite.parseObject(handling.body)!!
        val result = parsed["result"] as Map<*, *>
        expect(result["protocolVersion"] == McpProtocol.VERSION, "protocol version")
        expect((result["serverInfo"] as Map<*, *>)["name"] == McpProtocol.SERVER_NAME, "server name")
    }

    test("tools/list needs a key and lists exactly the five tools") {
        val anonymous = McpProtocol.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            caller = null, projects = projects, now = 0L, idFactory = { "x" }, executor = null
        )
        expect(errorCode(anonymous) == McpProtocol.UNAUTHORIZED, "anonymous listing refused")

        val handling = McpProtocol.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""",
            caller(Plan.METERED, 0, Scope.VERIFY, Scope.READ, Scope.BUILD),
            projects, 0L, { "x" }, null
        )
        val tools = (JsonLite.parseObject(handling.body)!!["result"] as Map<*, *>)["tools"] as List<*>
        expect(tools.size == VerifierTool.entries.size, "tool count ${tools.size}")
        val names = tools.map { (it as Map<*, *>)["name"] as String }
        expect(names.containsAll(listOf("lean.check", "lean.build", "lean.verify", "lean.sorry_scan", "lean.axioms")), names.toString())
        expect(tools.all { (it as Map<*, *>)["inputSchema"] != null }, "every tool declares a schema")
        val annotations = (tools.first() as Map<*, *>)["annotations"] as Map<*, *>
        expect(annotations["destructiveHint"] == false, "nothing is destructive")
        expect(annotations["openWorldHint"] == false, "nothing reaches the open world")
    }

    test("malformed and oversized bodies are parse errors, not crashes") {
        val notJson = McpProtocol.handle("not json", null, projects, 0L, { "x" }, null)
        expect(errorCode(notJson) == McpProtocol.PARSE_ERROR, "parse error")
        val tooBig = McpProtocol.handle("x".repeat(400_001), null, projects, 0L, { "x" }, null)
        expect(tooBig.status == 413, "oversize status ${tooBig.status}")
        val noMethod = McpProtocol.handle("""{"jsonrpc":"2.0","id":1}""", caller(), projects, 0L, { "x" }, null)
        expect(errorCode(noMethod) == McpProtocol.INVALID_REQUEST, "missing method")
    }

    test("unknown methods and unknown tools are refused") {
        val unknownMethod = McpProtocol.handle(
            """{"jsonrpc":"2.0","id":4,"method":"resources/list"}""",
            caller(), projects, 0L, { "x" }, null
        )
        expect(errorCode(unknownMethod) == McpProtocol.METHOD_NOT_FOUND, "unknown method")
        val unknownTool = call("lean.reboot", """{"project":"toe"}""", caller(Plan.METERED, 0, Scope.VERIFY), FakeHost())
        expect(errorCode(unknownTool) == McpProtocol.INVALID_PARAMS, "unknown tool")
    }

    // ── authorization and quota ──────────────────────────────────────
    test("scopes gate the expensive tools") {
        val verifyOnly = caller(Plan.METERED, 0, Scope.VERIFY)
        val build = call("lean.build", """{"project":"toe","module":"ToE"}""", verifyOnly, FakeHost())
        expect(errorCode(build) == McpProtocol.UNAUTHORIZED, "build without BUILD scope")
        val noVerify = caller(Plan.METERED, 0, Scope.READ)
        val check = call("lean.check", """{"project":"toe","source":"theorem a : True := trivial"}""", noVerify, FakeHost())
        expect(errorCode(check) == McpProtocol.UNAUTHORIZED, "check without VERIFY scope")
    }

    test("a key that cannot pay is refused before anything runs or is charged") {
        val host = FakeHost()
        var charged = 0
        // 4 credits left; lean.verify costs 8.
        val handling = call(
            "lean.verify",
            """{"project":"toe","module":"ToE"}""",
            caller(Plan.METERED, used = 4_996, Scope.VERIFY, Scope.BUILD),
            host,
            chargeSink = { charged += it }
        )
        expect(errorCode(handling) == McpProtocol.QUOTA_EXCEEDED, "quota error")
        expect(handling.status == 402, "payment required status ${handling.status}")
        expect(host.jobs.isEmpty(), "the host was not touched")
        expect(charged == 0, "nothing was charged")
    }

    test("a key that can pay is charged once, for the tool's price") {
        val host = FakeHost()
        var charged = 0
        val handling = call(
            "lean.verify",
            """{"project":"toe","module":"ToE"}""",
            caller(Plan.METERED, 0, Scope.VERIFY, Scope.BUILD),
            host,
            chargeSink = { charged += it }
        )
        expect(handling.status == 200, "status ${handling.status}")
        expect(host.jobs.size == 1, "one job")
        expect(charged == VerifierTool.VERIFY.credits, "charged $charged")
        val structured = (JsonLite.parseObject(handling.body)!!["result"] as Map<*, *>)["structuredContent"] as Map<*, *>
        expect(structured["ok"] == true, "verdict")
        expect((structured["chargedCredits"] as Number).toInt() == VerifierTool.VERIFY.credits, "charge echoed")
        expect(structured["transport"] == "fake", "transport echoed")
    }

    test("an internal key is never blocked and never charged") {
        val meter = MeterState("internal", Plan.INTERNAL, usedCredits = 9_999, periodStartEpochMs = 0L)
        val decision = Billing.authorize(meter, VerifierTool.VERIFY)
        expect(decision.allowed, "internal allowed")
        expect(decision.charge == 0, "no charge")
        expect(meter.remaining == Int.MAX_VALUE, "unbounded")
    }

    test("credits roll into a new period without losing the lifetime total") {
        val meter = MeterState("k", Plan.FREE, usedCredits = 50, periodStartEpochMs = 0L, lifetimeCredits = 50)
        val rolled = Billing.roll(meter, now = Billing.PERIOD_MILLIS + 1)
        expect(rolled.usedCredits == 0, "reset")
        expect(rolled.lifetimeCredits == 50, "lifetime kept")
        expect(rolled.remaining == Plan.FREE.creditsPerPeriod, "fresh quota")
        val same = Billing.roll(rolled, now = Billing.PERIOD_MILLIS + 10)
        expect(same.usedCredits == 0 && same.periodStartEpochMs == rolled.periodStartEpochMs, "no spurious roll")
    }

    test("dollars follow the plan, and the ledger totals them") {
        MeteringLedger.clear()
        expect(Billing.usdFor(100, Plan.METERED) > Billing.usdFor(100, Plan.ENTERPRISE), "enterprise is cheaper per credit")
        expect(Billing.usdFor(100, Plan.FREE) == 0.0, "free is free")
        MeteringLedger.record(UsageLine(10L, "a", VerifierTool.CHECK, 1, 0.012, true))
        MeteringLedger.record(UsageLine(20L, "b", VerifierTool.VERIFY, 8, 0.096, true))
        MeteringLedger.record(UsageLine(30L, "a", VerifierTool.BUILD, 5, 0.06, false))
        expect(MeteringLedger.totalCredits() == 14, "credits ${MeteringLedger.totalCredits()}")
        expect(MeteringLedger.byKey()["a"] == 6, "per key")
        expect(MeteringLedger.totalCredits(afterEpochMs = 15L) == 13, "windowed")
        repeat(MeteringLedger.MAX_LINES + 20) {
            MeteringLedger.record(UsageLine(100L + it, "c", VerifierTool.CHECK, 1, 0.012, true))
        }
        expect(MeteringLedger.snapshot().size == MeteringLedger.MAX_LINES, "bounded")
        expect(MeteringLedger.summary().contains("credits"), MeteringLedger.summary())
        MeteringLedger.clear()
    }

    // ── keys ─────────────────────────────────────────────────────────
    test("keys verify, do not collide, and revoke cleanly") {
        val (record, token) = key(Plan.METERED, Scope.VERIFY)
        expect(token.startsWith("lvs_"), "prefix")
        expect(ApiKeys.verify(record, token), "correct token")
        val flipped = (if (token.last() == 'x') 'y' else 'x')
        expect(!ApiKeys.verify(record, token.dropLast(1) + flipped), "wrong secret")
        expect(!ApiKeys.verify(record.copy(hash = "0".repeat(64)), token), "wrong hash")
        expect(!ApiKeys.verify(record.copy(revoked = true), token), "revoked")
        val (other, otherToken) = key(Plan.FREE, Scope.READ)
        expect(other.hash != record.hash, "distinct hashes")
        expect(ApiKeys.find(listOf(record, other), token)?.id == record.id, "found by id")
        expect(ApiKeys.find(listOf(record, other), otherToken)?.id == other.id, "found the other")
        expect(ApiKeys.find(listOf(record), "lvs_nope_deadbeef") == null, "unknown id")
    }

    test("authorization headers are parsed strictly") {
        val (_, token) = key(Plan.FREE, Scope.READ)
        expect(ApiKeys.tokenFromHeader("Bearer $token") == token, "standard")
        expect(ApiKeys.tokenFromHeader("bearer $token") == token, "case-insensitive scheme")
        expect(ApiKeys.tokenFromHeader("Basic $token") == null, "wrong scheme")
        expect(ApiKeys.tokenFromHeader(token) == null, "no scheme")
        expect(ApiKeys.tokenFromHeader("Bearer sky_abc") == null, "wrong prefix")
        expect(ApiKeys.tokenFromHeader(null) == null, "null")
        expect(ApiKeys.tokenFromHeader("Bearer ") == null, "empty")
    }

    // ── source screening ─────────────────────────────────────────────
    test("source that executes code is refused with a line number") {
        val cases = listOf(
            "#eval 1 + 1" to "#eval",
            "def boom : Nat := 0\nrun_cmd pure ()" to "run_cmd",
            "unsafe def cast (a : Nat) : String := unsafeCast a" to "unsafe",
            "@[extern \"c_thing\"] def thing : Nat := 0" to "@[extern",
            "import Lean.Elab.Command" to "imports",
            "def readIt : IO Unit := IO.FS.readFile \"/etc/passwd\"" to "IO.FS"
        )
        for ((source, needle) in cases) {
            val verdict = SourceGuard.inspect(source)
            expect(!verdict.ok, "should refuse: ${source.take(30)}")
            expect(verdict.reason!!.contains(needle, ignoreCase = true), "reason for ${source.take(20)}: ${verdict.reason}")
            expect(verdict.line != null, "line number reported")
        }
    }

    test("ordinary proof text passes, and a comment cannot trip the screen") {
        val fine = """
            import Mathlib
            open Real
            -- #eval 1+1 is a comment, not code
            theorem sq_nonneg (x : ℝ) : 0 ≤ x^2 := sq_nonneg x
        """.trimIndent()
        val verdict = SourceGuard.inspect(fine)
        expect(verdict.ok, "clean source rejected: ${verdict.reason}")
        expect(!SourceGuard.inspect("").ok, "empty source")
        expect(!SourceGuard.inspect("x".repeat(SourceGuard.MAX_SOURCE_CHARS + 1)).ok, "oversize source")
        expect(!SourceGuard.inspect("def x := 1\u0000").ok, "NUL byte")
    }

    test("placeholders are counted, comments are not") {
        val source = """
            theorem a : True := by sorry
            -- sorry in a comment
            theorem b : True := by admit
            axiom cheat : False
        """.trimIndent()
        expect(SourceGuard.countPlaceholders(source) == 3, "count ${SourceGuard.countPlaceholders(source)}")
        expect(SourceGuard.countPlaceholders("theorem a : True := trivial") == 0, "clean")
    }

    // ── planning ─────────────────────────────────────────────────────
    test("unknown projects, bad modules and missing arguments are refused") {
        val host = FakeHost()
        val unknownProject = call("lean.check", """{"project":"nope","source":"theorem a : True := trivial"}""", caller(), host)
        expect(errorCode(unknownProject) == McpProtocol.REFUSED, "unknown project")
        val builder = caller(Plan.METERED, 0, Scope.VERIFY, Scope.BUILD)
        val badModule = call("lean.build", """{"project":"toe","module":"ToE; rm -rf /"}""", builder, host)
        expect(errorCode(badModule) == McpProtocol.REFUSED, "bad module")
        val noSource = call("lean.check", """{"project":"toe"}""", caller(), host)
        expect(errorCode(noSource) == McpProtocol.REFUSED, "check without source")
        val noDeclarations = call("lean.axioms", """{"project":"toe"}""", caller(), host)
        expect(errorCode(noDeclarations) == McpProtocol.REFUSED, "axioms without declarations")
        val sourceOnBuild = call("lean.build", """{"project":"toe","module":"ToE","source":"theorem a : True := trivial"}""", builder, host)
        expect(errorCode(sourceOnBuild) == McpProtocol.REFUSED, "build with source")
        expect(host.jobs.isEmpty(), "nothing reached the host")
    }

    test("timeouts are clamped to the tool's ceiling") {
        val host = FakeHost()
        call(
            "lean.check",
            """{"project":"toe","source":"theorem a : True := trivial","timeout_seconds":99999}""",
            caller(), host
        )
        expect(host.jobs.single().timeoutSeconds == VerifierTool.CHECK.timeoutSeconds, "clamped up")
        val host2 = FakeHost()
        call(
            "lean.check",
            """{"project":"toe","source":"theorem a : True := trivial","timeout_seconds":1}""",
            caller(), host2
        )
        expect(host2.jobs.single().timeoutSeconds == 10, "clamped down")
    }

    test("the remote command is bounded, cleaned up, and never contains raw source") {
        val nasty = "theorem a : True := by\n  have h := \"'\\\$(reboot) `id`\"\n  trivial"
        val handled = call(
            "lean.check",
            """{"project":"toe","source":${JsonLite.quote(nasty)}}""",
            caller(), FakeHost()
        )
        val job = handled.job!!
        val command = JobPlanner.remoteCommand(job)
        expect(!command.contains("reboot"), "raw source leaked into the command")
        expect(!command.contains("`id`"), "raw source leaked into the command")
        expect(command.contains("base64 -d"), "source travels encoded")
        expect(command.contains("mktemp -d"), "fresh directory")
        expect(command.contains("trap 'rm -rf"), "cleanup")
        expect(command.contains("timeout ${job.timeoutSeconds}"), "wall clock")
        expect(command.contains("LUCIFER_EXIT="), "exit marker")
        expect(command.contains("lake env lean"), "elaborates with lake")
    }

    test("build, sorry scan and axioms plan distinct commands") {
        val builder = caller(Plan.METERED, 0, Scope.VERIFY, Scope.BUILD)
        val build = call("lean.build", """{"project":"toe","module":"ToE.Physics.Spacetime"}""", builder, FakeHost()).job!!
        expect(JobPlanner.remoteCommand(build).contains("lake build ToE.Physics.Spacetime"), "build module")
        val scan = call("lean.sorry_scan", """{"project":"toe"}""", builder, FakeHost()).job!!
        expect(JobPlanner.remoteCommand(scan).contains("LUCIFER_SORRY="), "scan marker")
        val axioms = call(
            "lean.axioms",
            """{"project":"toe","declarations":["ToE.main","ToE.energy"]}""",
            builder, FakeHost()
        ).job!!
        val axiomsCommand = JobPlanner.remoteCommand(axioms)
        expect(axiomsCommand.contains("base64 -d"), "axioms file encoded")
        expect(!axiomsCommand.contains("#print axioms ToE.main"), "declarations are not interpolated raw")
    }

    test("base64 round-trips, including multibyte text") {
        val samples = listOf("", "a", "ab", "abc", "theorem x : True := trivial", "∀ ε > 0, ∃ δ", "line1\nline2\r\n")
        for (sample in samples) {
            val encoded = JobPlanner.encode(sample)
            val decoded = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            expect(decoded == sample, "round trip failed for `${sample.take(20)}` -> $encoded")
        }
    }

    // ── results ──────────────────────────────────────────────────────
    test("a log tail becomes a verdict") {
        val output = listOf(
            "Check.lean:3:2: error: unknown identifier 'foo'",
            "warning: declaration uses 'sorry'",
            "ToE.main' depends on axioms: [propext, Classical.choice, Quot.sound]",
            "LUCIFER_SORRY=1",
            "LUCIFER_EXIT=1"
        )
        val result = ResultParser.parse(output, "ssh", 1_234L)
        expect(!result.ok, "failure detected")
        expect(result.exitCode == 1, "exit")
        expect(result.errors.size == 1, "errors")
        expect(result.sorryCount == 1, "sorries")
        expect(result.axioms.single().startsWith("ToE.main"), result.axioms.toString())
        expect(result.elapsedMs == 1_234L, "elapsed")
        expect(result.output.none { it.contains("LUCIFER_EXIT") }, "marker stripped from output")

        val clean = ResultParser.parse(listOf("ok", "LUCIFER_EXIT=0"), "http", 10L)
        expect(clean.ok, "clean verdict")
    }

    // ── projects and exposure ────────────────────────────────────────
    test("projects are validated, deduplicated, and never come from the caller") {
        val parsed = ProjectRegistry.parse(
            listOf(
                mapOf("id" to "toe", "label" to "Omega", "root" to "/home/droid/omega", "module" to "ToE"),
                mapOf("id" to "toe", "label" to "dupe", "root" to "/tmp/other"),
                mapOf("id" to "Bad Id", "root" to "/tmp/x"),
                mapOf("id" to "escape", "root" to "/home/droid/../etc"),
                mapOf("id" to "rel", "root" to "relative/path"),
                mapOf("id" to "toe2", "root" to "/home/droid/omega2", "module" to "not a module")
            )
        )
        expect(parsed.map { it.id } == listOf("toe", "toe2"), parsed.map { it.id }.toString())
        expect(parsed.first().root == "/home/droid/omega", "first wins")
        expect(parsed.last().defaultModule == null, "bad module dropped, project kept")
    }

    test("the tunnel never puts its token on the command line") {
        val exposure = ExposurePlan.cloudflare("verifier.lucifer.example")
        expect(exposure.kind == "cloudflare", "kind")
        expect(exposure.hostname == "verifier.lucifer.example", "hostname")
        expect(exposure.command.contains("TUNNEL_TOKEN=\$(cat"), "token read from file")
        expect(!exposure.command.contains("--token "), "token must not be an argument")
        expect(exposure.command.contains("127.0.0.1:${ExposurePlan.DEFAULT_PORT}"), "loopback target")
        expect(exposure.command.contains("chmod 600") || exposure.command.contains("chmod 700"), "restrictive dir")
        val bad = runCatching { ExposurePlan.cloudflare("not a hostname") }
        expect(bad.isFailure, "invalid hostname rejected")
        val ssh = ExposurePlan.sshReverse("vm.example.com", "droid", 8791)
        expect(ssh.command.contains("-R 443:127.0.0.1:8791"), ssh.command)
        expect(ssh.command.contains("StrictHostKeyChecking=yes"), "pinned host key")
        expect(ExposurePlan.localOnly().kind == "loopback", "loopback option exists")
    }

    println()
    if (failures.isEmpty()) {
        println("PASS · $passed checks")
    } else {
        println("FAILED · $passed passed, ${failures.size} failed")
        failures.forEach { println(" - $it") }
        kotlin.system.exitProcess(1)
    }
}
