// Host-side verification for the verifier's runtime and transport.
//
// The control plane (`VerificationCore`) is checked elsewhere. This file checks the parts that hold
// state and speak TCP: key persistence, meters that survive a restart, the charging rule, the command
// tripwire, and a real loopback HTTP exchange.
//
// Not part of the Android build. Run with scripts/verify_verification_core.sh.
package com.lucifer.mobile.verification.server.harness

import com.lucifer.mobile.verification.ApiKeys
import com.lucifer.mobile.verification.AuditVaultContract
import com.lucifer.mobile.verification.AuditVaultExecutor
import com.lucifer.mobile.verification.ExecutorRouter
import com.lucifer.mobile.verification.HealthProbe
import com.lucifer.mobile.verification.VaultTransport
import com.lucifer.mobile.verification.HttpFraming
import com.lucifer.mobile.verification.JobCommandGuard
import com.lucifer.mobile.verification.JobExecutor
import com.lucifer.mobile.verification.JobPlanner
import com.lucifer.mobile.verification.JobResult
import com.lucifer.mobile.verification.LeanJobSpec
import com.lucifer.mobile.verification.LeanProject
import com.lucifer.mobile.verification.McpProtocol
import com.lucifer.mobile.verification.MemoryVerifierStore
import com.lucifer.mobile.verification.Plan
import com.lucifer.mobile.verification.Scope
import com.lucifer.mobile.verification.VerifierCodec
import com.lucifer.mobile.verification.VerifierEndpoint
import com.lucifer.mobile.verification.VerifierRuntime
import com.lucifer.mobile.verification.VerifierTool
import com.lucifer.mobile.verification.VerifierUiState
import com.lucifer.mobile.resilience.JsonLite
import java.net.HttpURLConnection
import java.net.URL

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

private class RecordingHost(
    private val result: JobResult = JobResult(true, 0, listOf("ok"), transport = "fake")
) : JobExecutor {
    val jobs = mutableListOf<LeanJobSpec>()
    override fun execute(job: LeanJobSpec): JobResult {
        jobs += job
        return result
    }
}

private fun runtime(
    store: MemoryVerifierStore = MemoryVerifierStore(),
    executor: JobExecutor? = RecordingHost(),
    now: Long = 1_000L
): VerifierRuntime = VerifierRuntime(store, executor, clock = { now }, jobIds = { "job-fixed" })

private fun callBody(tool: String, arguments: String): String =
    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

private const val CHECK_ARGS = """{"project":"toe","module":"ToE","source":"theorem lucifer_probe : True := trivial"}"""

fun main() {
    println("Lucifer Lean verification server · runtime and transport")

    // ── persistence ──────────────────────────────────────────────────
    test("keys persist as hashes only, and round-trip") {
        val store = MemoryVerifierStore()
        val first = runtime(store)
        val issued = first.issueKey("customer", Plan.METERED, setOf(Scope.VERIFY, Scope.READ))
        val raw = store.read(VerifierCodec.KEYS_FILE).orEmpty()
        expect(raw.contains(issued.record.hash), "hash stored")
        expect(!raw.contains(issued.token), "the token is never stored")

        val reloaded = runtime(store)
        expect(reloaded.keys().size == 1, "one key after reload")
        expect(reloaded.keys().first().plan == Plan.METERED, "plan survives")
        expect(reloaded.keys().first().scopes == setOf(Scope.VERIFY, Scope.READ), "scopes survive")
    }

    test("a corrupt key file resurrects nothing") {
        val store = MemoryVerifierStore(
            mapOf(
                VerifierCodec.KEYS_FILE to
                    """[{"id":"k1234567","hash":"short","plan":"METERED","scopes":["VERIFY"]},"""
            )
        )
        expect(runtime(store).keys().isEmpty(), "garbage dropped")
    }

    test("ledger and project codecs accept what they wrote and reject nonsense") {
        val projects = listOf(LeanProject("toe", "Omega", "/srv/lean/omega", "ToE"))
        val decoded = VerifierCodec.decodeProjects(VerifierCodec.encodeProjects(projects))
        expect(decoded == projects, decoded.toString())
        expect(VerifierCodec.decodeProjects("""[{"id":"x","root":"relative"}]""").isEmpty(), "relative root dropped")

        val lines = listOf(
            com.lucifer.mobile.verification.UsageLine(5L, "k1", VerifierTool.CHECK, 1, 0.012, true)
        )
        val back = VerifierCodec.decodeLedger(VerifierCodec.encodeLedger(lines))
        expect(back.size == 1 && back.first().tool == VerifierTool.CHECK, back.toString())
        expect(back.first().usd == 0.012, back.first().usd.toString())
        expect(VerifierCodec.decodeLedger("""[{"keyId":"k1","tool":"lean.nope"}]""").isEmpty(), "unknown tool dropped")
    }

    // ── authentication and metering ─────────────────────────────────
    test("the handshake is free and everything else needs a key") {
        val runtime = runtime()
        val init = runtime.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", null)
        expect(init.status == 200 && init.body.contains("protocolVersion"), init.body)

        val list = runtime.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", null)
        expect(list.status == 401, "list without key refused")

        val bogus = runtime.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""",
            "Bearer lvs_k0000000_aaaaaaaaaaaaaaaaaaaaaaaa"
        )
        expect(bogus.status == 401, "unknown key refused")
    }

    test("a metered key is charged once, and the meter and ledger survive a restart") {
        val store = MemoryVerifierStore()
        val host = RecordingHost()
        val runtime = runtime(store, host)
        val issued = runtime.issueKey("customer", Plan.METERED, setOf(Scope.VERIFY, Scope.BUILD))
        val handling = runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}")
        expect(handling.status == 200, handling.body)
        expect(host.jobs.size == 1, "the job reached the host")
        expect(handling.charge == VerifierTool.CHECK.credits, handling.charge.toString())

        val meter = runtime.meterFor(issued.record.id)
        expect(meter?.usedCredits == 1, meter?.usedCredits.toString())
        expect(runtime.usage().credits == 1, runtime.usage().credits.toString())
        expect(runtime.usage().usd > 0.0, "usd follows the plan")

        val reloaded = runtime(store)
        expect(reloaded.meterFor(issued.record.id)?.usedCredits == 1, "meter persisted")
        expect(reloaded.usage().lines == 1, "ledger persisted")
        expect(reloaded.ledger().single().ok, "the line records the verdict")
    }

    test("work that never reached a host is never billed") {
        val runtime = runtime(executor = null)
        val issued = runtime.issueKey("customer", Plan.METERED, setOf(Scope.VERIFY, Scope.BUILD))
        val handling = runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}")
        expect(handling.charge > 0, "the tool still has a price")
        expect(runtime.meterFor(issued.record.id)?.usedCredits == 0, "nothing charged")
        expect(runtime.usage().lines == 0, "no ledger line")
    }

    test("a refused request is never charged and never runs") {
        val host = RecordingHost()
        val runtime = runtime(executor = host)
        val issued = runtime.issueKey("customer", Plan.METERED, setOf(Scope.VERIFY))
        val handling = runtime.handle(
            callBody("lean.build", """{"project":"toe","module":"ToE"}"""),
            "Bearer ${issued.token}"
        )
        expect(handling.status == 403, "no BUILD scope")
        expect(host.jobs.isEmpty(), "nothing ran")
        expect(runtime.meterFor(issued.record.id)?.usedCredits == 0, "nothing charged")

        val unknown = runtime.handle(
            callBody("lean.check", """{"project":"nope","module":"ToE","source":"theorem a : True := trivial"}"""),
            "Bearer ${issued.token}"
        )
        expect(unknown.status == 422, "unknown project refused")
        expect(runtime.meterFor(issued.record.id)?.usedCredits == 0, "still nothing charged")
    }

    test("exhausted quota is refused before the host is touched") {
        val host = RecordingHost()
        val store = MemoryVerifierStore()
        val runtime = runtime(store, host)
        val issued = runtime.issueKey("free", Plan.FREE, setOf(Scope.VERIFY, Scope.BUILD))
        // 50 credits, one per check: spend all of them and the fifty-first is refused.
        val spend = runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}")
        expect(spend.status == 200, spend.body)
        // Now push the meter to the ceiling through the runtime's own accounting.
        repeat(49) { runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}") }
        val before = host.jobs.size
        val refused = runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}")
        expect(refused.status == 402, "quota refused: ${refused.status}")
        expect(host.jobs.size == before, "the host was not touched")
    }

    test("a revoked key is refused everywhere") {
        val host = RecordingHost()
        val runtime = runtime(executor = host)
        val issued = runtime.issueKey("customer", Plan.METERED, setOf(Scope.VERIFY, Scope.BUILD))
        expect(runtime.revokeKey(issued.record.id), "revoked")
        expect(!runtime.revokeKey(issued.record.id), "second revoke is a no-op")
        val handling = runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}")
        expect(handling.status == 401, handling.status.toString())
        expect(host.jobs.isEmpty(), "nothing ran")
    }

    test("an internal key is never blocked and never charged") {
        val runtime = runtime(executor = RecordingHost())
        val issued = runtime.ensureInternalKey()!!
        expect(runtime.ensureInternalKey() == null, "only one internal key")
        repeat(3) {
            val handling = runtime.handle(callBody("lean.check", CHECK_ARGS), "Bearer ${issued.token}")
            expect(handling.status == 200, handling.body)
            expect(handling.charge == 0, "free for Lucifer")
        }
        expect(runtime.meterFor(issued.record.id)?.remaining == Int.MAX_VALUE, "unbounded")
        expect(runtime.usage().lines == 0, "no billable lines")
    }

    test("Lucifer can call his own server without a token, and is still metered as internal") {
        val host = RecordingHost()
        val runtime = runtime(executor = host)
        val body = """{"jsonrpc":"2.0","id":"owner","method":"tools/call","params":{"name":"lean.check",""" +
            """"arguments":{"project":"toe","module":"ToE","source":"theorem lucifer : True := trivial"}}}"""
        val handling = runtime.ownerCall(body)
        expect(handling.status == 200, handling.body)
        expect(host.jobs.size == 1, "the job ran")
        expect(handling.charge == 0, "internal is free")
        val key = runtime.keys().single()
        expect(key.plan == Plan.INTERNAL, key.plan.name)
        expect(key.scopes.contains(Scope.BUILD), "internal scope is complete")

        val unknown = runtime.ownerCall(
            """{"jsonrpc":"2.0","id":"owner","method":"tools/call","params":{"name":"lean.check",""" +
                """"arguments":{"project":"not-configured","source":"theorem a : True := trivial"}}}"""
        )
        expect(unknown.status == 422, "the owner is screened too")
        expect(host.jobs.size == 1, "and nothing ran")

        val handshake = runtime.ownerCall("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        expect(handshake.status == 200, "the owner can list tools")
    }

    // ── the command tripwire ────────────────────────────────────────
    test("the tripwire accepts every planned command and refuses a tampered one") {
        val project = LeanProject("toe", "Omega", "/srv/lean/omega", "ToE")
        val jobs = listOf(
            job(project, VerifierTool.CHECK, module = "ToE", source = "theorem a : True := trivial"),
            job(project, VerifierTool.BUILD, module = "ToE.Physics.Spacetime"),
            job(project, VerifierTool.VERIFY, module = null),
            job(project, VerifierTool.SORRY_SCAN, module = null),
            job(project, VerifierTool.AXIOMS, module = "ToE", declarations = listOf("ToE.main"))
        )
        jobs.forEach { spec ->
            val command = JobPlanner.remoteCommand(spec)
            val refusal = JobCommandGuard.check(command, spec)
            expect(refusal == null, "${spec.tool.wireName}: $refusal")
            expect(!command.contains(spec.source.orEmpty().take(20).ifEmpty { "\u0000none" }), "no raw source")
        }

        val check = jobs.first()
        val command = JobPlanner.remoteCommand(check)
        expect(JobCommandGuard.check(command.replace("timeout 120 ", ""), check) != null, "missing timeout")
        expect(JobCommandGuard.check(command.replace("mktemp -d", "mktemp"), check) != null, "no temp dir")
        expect(
            JobCommandGuard.check(command.replace("trap 'rm -rf", "echo 'rm -rf"), check) != null,
            "no cleanup"
        )
        expect(JobCommandGuard.check("", check) != null, "empty command")
        expect(JobCommandGuard.check(command.replace(check.project.root, "/etc"), check) != null, "moved root")
    }

    // ── HTTP ────────────────────────────────────────────────────────
    test("a real socket serves the handshake, health, and refuses everything else") {
        val runtime = runtime(executor = RecordingHost())
        val issued = runtime.issueKey("customer", Plan.METERED, setOf(Scope.VERIFY, Scope.BUILD, Scope.READ))
        val endpoint = VerifierEndpoint(runtime, port = 0)
        val port = endpoint.start()
        try {
            expect(port > 0, "bound a port")
            val init = post(port, "/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""", null)
            expect(init.first == 200 && init.second.contains("serverInfo"), init.toString())

            val unauth = post(port, "/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", null)
            expect(unauth.first == 401, unauth.toString())

            val list = post(
                port,
                "/mcp",
                """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""",
                "Bearer ${issued.token}"
            )
            expect(list.first == 200 && list.second.contains("lean.check"), list.toString())

            val health = get(port, "/health")
            expect(health.first == 200, health.toString())
            expect(health.second.contains("lucifer-lean-verifier"), health.second)
            expect(!health.second.contains(issued.record.hash), "health leaks no key material")
            expect(!health.second.contains(issued.token), "health leaks no token")

            val missing = get(port, "/admin")
            expect(missing.first == 404, missing.toString())

            val wrongMethod = get(port, "/mcp")
            expect(wrongMethod.first == 405, wrongMethod.toString())
        } finally {
            endpoint.stop()
        }
        expect(!endpoint.running, "stopped cleanly")
    }

    test("an oversized body is refused before it is read into memory") {
        val runtime = runtime(executor = RecordingHost())
        val endpoint = VerifierEndpoint(runtime, port = 0)
        val port = endpoint.start()
        try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                val out = socket.getOutputStream()
                out.write(
                    (
                        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            "Content-Length: ${HttpFraming.MAX_BODY + 1}\r\n\r\n"
                        ).toByteArray()
                )
                out.flush()
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                expect(response.startsWith("HTTP/1.1 413"), response.take(40))
            }
        } finally {
            endpoint.stop()
        }
    }

    test("the framing rejects junk instead of guessing") {
        val junk = HttpFraming.read("not a request\r\n\r\n".byteInputStream())
        expect(junk is HttpFraming.Read.Failed, "malformed request line")

        val chunked = HttpFraming.read(
            (
                "POST /mcp HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
                ).byteInputStream()
        )
        expect(chunked is HttpFraming.Read.Failed && chunked.status == 411, "chunked refused")

        val get = HttpFraming.read("GET /health HTTP/1.1\r\nHost: x\r\n\r\n".byteInputStream())
        expect(get is HttpFraming.Read.Ok && get.request.path == "/health", "GET parsed")

        val noLength = HttpFraming.read("POST /mcp HTTP/1.1\r\nHost: x\r\n\r\n".byteInputStream())
        expect(noLength is HttpFraming.Read.Ok && noLength.request.body.isEmpty(), "empty body accepted")
    }


    // ── audit-vault backend ─────────────────────────────────────────
    test("the vault receives a structured job, never a shell string") {
        val spec = job(toe(), VerifierTool.CHECK, module = "ToE", source = "theorem lucifer : True := trivial")
        val body = AuditVaultContract.jobBody(spec)
        expect(body.contains("\"contract\":1"), body)
        expect(body.contains("\"tool\":\"lean.check\""), "tool named")
        expect(body.contains("\"timeout_seconds\":120"), "ceiling sent")
        expect(body.contains("\"job\":\"${spec.id}\""), "job id echoed for idempotency")
        expect(body.contains("source_b64"), "source travels as base64")
        expect(body.contains(com.lucifer.mobile.verification.JobPlanner.encode(spec.source!!)), "base64 payload present")
        expect(!body.contains("theorem lucifer"), "raw source is not in the request")
        listOf("base64 -d", "mktemp", "trap ", "sh -c", "lake build", "set -u").forEach { token ->
            expect(!body.contains(token), "request must not carry shell text: $token")
        }
        val json = JsonLite.parseObject(body)!!
        expect((json["project"] as? Map<*, *>)?.get("root") == "/srv/lean/omega", "root is configuration")
    }

    test("requests are signed over body, time and secret") {
        val spec = job(toe(), VerifierTool.CHECK, module = "ToE", source = "theorem a : True := trivial")
        val headers = AuditVaultContract.headers(spec, "s3cret", 1_700_000_000_000L)
        expect(headers[AuditVaultContract.HEADER_JOB] == spec.id, "job header")
        expect(headers[AuditVaultContract.HEADER_CONTRACT] == "1", "contract header")
        expect(headers[AuditVaultContract.HEADER_TIMESTAMP] == "1700000000000", "timestamp header")
        val signature = headers[AuditVaultContract.HEADER_SIGNATURE]!!
        expect(signature.length == 64 && signature.all { it in "0123456789abcdef" }, signature)
        expect(
            signature == AuditVaultContract.sign("s3cret", 1_700_000_000_000L, AuditVaultContract.jobBody(spec)),
            "signature is reproducible"
        )
        expect(
            signature != AuditVaultContract.sign("s3cret", 1_700_000_001_000L, AuditVaultContract.jobBody(spec)),
            "time is bound"
        )
        expect(
            signature != AuditVaultContract.sign("other", 1_700_000_000_000L, AuditVaultContract.jobBody(spec)),
            "secret is bound"
        )
        val tampered = AuditVaultContract.jobBody(spec).replace("lean.check", "lean.verify")
        expect(signature != AuditVaultContract.sign("s3cret", 1_700_000_000_000L, tampered), "body is bound")
        expect(!headers.values.any { it.contains("s3cret") }, "the secret never travels")
    }

    test("the phone only signs for URLs it can defend") {
        expect(AuditVaultContract.validUrl("https://vault.example.com"), "https")
        expect(AuditVaultContract.validUrl("https://vault.example.com/lean"), "https with a path")
        expect(AuditVaultContract.normalizeUrl("https://vault.example.com/") == "https://vault.example.com", "trailing slash")
        expect(AuditVaultContract.validUrl("http://127.0.0.1:8080"), "loopback over http")
        expect(AuditVaultContract.validUrl("http://192.168.1.20:8080"), "private over http")
        expect(!AuditVaultContract.validUrl("http://vault.example.com"), "public http is refused")
        expect(!AuditVaultContract.validUrl("https://user:pw@vault.example.com"), "credentials in the URL refused")
        expect(!AuditVaultContract.validUrl("https://vault.example.com/?x=1"), "query refused")
        expect(!AuditVaultContract.validUrl("ftp://vault.example.com"), "scheme refused")
        expect(!AuditVaultContract.validUrl(""), "empty refused")
    }

    test("the verdict is the phone's, not the vault's") {
        val spec = job(toe(), VerifierTool.CHECK, module = "ToE", source = "theorem a : True := trivial")
        val good = AuditVaultContract.parseJob(vaultReply(spec), spec)
        expect(good.ok && good.transport == "audit-vault", good.transport)
        expect(good.exitCode == 0 && good.elapsedMs == 1_234L, "primitives carried")

        val failed = AuditVaultContract.parseJob(
            vaultReply(spec, exit = 1, errors = listOf("unknown identifier 'lucifer'")),
            spec
        )
        expect(!failed.ok && failed.transport == "audit-vault", "a failed proof is still a verdict")
        expect(failed.errors.single().contains("unknown identifier"), "the reason survives")

        val forged = AuditVaultContract.parseJob(vaultReply(spec, attest = false), spec)
        expect(!forged.ok, "attestation mismatch is not a pass")
        expect(forged.transport == "audit-vault-unverified", forged.transport)
        expect(forged.errors.any { it.contains("attestation") }, forged.errors.toString())

        val wrongJob = AuditVaultContract.parseJob(
            vaultReply(spec).replace(spec.id, "job-someone-else"),
            spec
        )
        expect(wrongJob.transport == "none" && !wrongJob.ok, "a reply for another job is discarded")

        val wrongContract = AuditVaultContract.parseJob(vaultReply(spec).replace("\"contract\":1", "\"contract\":2"), spec)
        expect(wrongContract.transport == "none", "unknown contract discarded")

        val liar = AuditVaultContract.parseJob(
            vaultReply(spec, exit = 1, errors = emptyList()).replace("\"ok\":false", "\"ok\":true"),
            spec
        )
        expect(!liar.ok, "the vault cannot declare a pass over a failing exit")
        expect(liar.errors.any { it.contains("claimed ok") }, liar.errors.toString())

        val noisy = AuditVaultContract.parseJob(vaultReply(spec, lines = 900), spec)
        expect(noisy.output.size == AuditVaultContract.MAX_OUTPUT_LINES, noisy.output.size.toString())
    }

    test("an outage is not a verdict") {
        val spec = job(toe(), VerifierTool.BUILD, module = "ToE")

        val throwing = FakeVault(onPost = { _, _, _ -> throw java.io.IOException("connection refused") })
        val result = AuditVaultExecutor(throwing, "secret").execute(spec)
        expect(result.transport == "none" && !result.ok, result.transport)

        val down = FakeVault(onPost = { _, _, _ -> VaultTransport.Reply(503, "{}") })
        expect(AuditVaultExecutor(down, "secret").execute(spec).transport == "none", "503 is not a verdict")

        val healthy = FakeVault(onPost = { _, _, _ -> VaultTransport.Reply(200, vaultReply(spec)) })
        val ok = AuditVaultExecutor(healthy, "secret", clock = { 42L }).execute(spec)
        expect(ok.transport == "audit-vault" && ok.ok, ok.transport)
        expect(healthy.posts.single().first == AuditVaultContract.PATH_JOBS, "posted to the job path")
        expect(healthy.posts.single().second.containsKey(AuditVaultContract.HEADER_SIGNATURE), "signed")
    }

    test("health is probed at most once per window and parsed properly") {
        val spec = job(toe(), VerifierTool.CHECK, module = "ToE", source = "theorem a : True := trivial")
        val vault = FakeVault(
            onGet = {
                VaultTransport.Reply(
                    200,
                    JsonLite.stringify(
                        linkedMapOf(
                            "contract" to 1,
                            "name" to "audit-vault",
                            "version" to "0.3.1",
                            "tools" to listOf("lean.check", "lean.verify"),
                            "max_timeout_seconds" to 1_200
                        )
                    )
                )
            }
        )
        var now = 1_000L
        val probe = HealthProbe(vault, ttlMs = 30_000L, clock = { now })
        expect(probe.available(), "healthy")
        expect(probe.latest?.maxTimeoutSeconds == 1_200, probe.latest.toString())
        expect(probe.latest?.supports(spec.tool) == true, "tool supported")
        expect(probe.describe().startsWith("up"), probe.describe())
        expect(vault.gets == 1, "one probe")
        now += 1_000
        probe.available()
        expect(vault.gets == 1, "cached within the window")
        now += 40_000
        probe.available()
        expect(vault.gets == 2, "re-probed after the window")

        val broken = HealthProbe(FakeVault(onGet = { VaultTransport.Reply(200, "not json") }))
        expect(!broken.available(), "garbage health is down")
        expect(broken.describe() == "down", broken.describe())
    }

    test("the router falls back only when the backend did not answer") {
        val spec = job(toe(), VerifierTool.CHECK, module = "ToE", source = "theorem a : True := trivial")
        val vm = RecordingHost(JobResult(true, 0, listOf("vm ran it"), transport = "vm-ssh"))

        val outage = FakeVault(onPost = { _, _, _ -> VaultTransport.Reply(503, "") })
        val fellBack = ExecutorRouter(AuditVaultExecutor(outage, "s"), vm).execute(spec)
        expect(fellBack.transport == "vm-ssh" && fellBack.ok, fellBack.transport)
        expect(fellBack.output.first().contains("audit-vault"), fellBack.output.first())
        expect(vm.jobs.size == 1, "the fallback ran")

        val verifier = RecordingHost(JobResult(false, 1, listOf("error"), errors = listOf("bad proof"), transport = "audit-vault"))
        val notFellBack = ExecutorRouter(verifier, vm).execute(spec)
        expect(notFellBack.transport == "audit-vault" && !notFellBack.ok, "a verdict is not retried")

        val skipping = RecordingHost(JobResult(true, 0, listOf("unused"), transport = "audit-vault"))
        val skipped = ExecutorRouter(skipping, vm, primaryAvailable = { false }).execute(spec)
        expect(skipped.transport == "vm-ssh", skipped.transport)
        expect(skipping.jobs.isEmpty(), "an unhealthy backend is not called")

        val alone = ExecutorRouter(null, null).execute(spec)
        expect(alone.transport == "none" && !alone.ok, "no backend is an honest failure")
        expect(alone.output.first().contains("No fallback"), alone.output.first())
    }

    // ── ui state ────────────────────────────────────────────────────
    test("the panel never renders a secret it was not handed") {
        val runtime = runtime(executor = RecordingHost())
        val issued = runtime.issueKey("customer", Plan.FREE, setOf(Scope.VERIFY, Scope.READ))
        val state = VerifierUiState.from(runtime, running = true, port = 8791)
        expect(state.running, "running")
        expect(state.endpoint == "http://127.0.0.1:8791/mcp", state.endpoint)
        expect(state.projects.single().startsWith("toe → /"), state.projects.toString())
        expect(state.keys.size == 1, "one key")
        expect(state.issuedToken == null, "no token in a refreshed state")
        expect(!state.toString().contains(issued.token), "no token in the view")
        expect(state.keys.first().remaining == 50, state.keys.first().remaining.toString())

        val withToken = VerifierUiState.from(runtime, running = true, issuedToken = issued.token)
        expect(withToken.issuedToken == issued.token, "only an explicit pass carries the token")
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

private var jobSeed = 0

private fun job(
    project: LeanProject,
    tool: VerifierTool,
    module: String?,
    source: String? = null,
    declarations: List<String> = emptyList()
): LeanJobSpec = LeanJobSpec(
    id = "job-${jobSeed++}",
    tool = tool,
    project = project,
    module = module,
    declarations = declarations,
    source = source,
    timeoutSeconds = tool.timeoutSeconds,
    keyId = "k-test",
    issuedAt = 1_000L,
    charge = tool.credits
)

private fun post(port: Int, path: String, body: String, authorization: String?): Pair<Int, String> {
    val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("Content-Type", "application/json")
    if (authorization != null) connection.setRequestProperty("Authorization", authorization)
    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val status = connection.responseCode
    val stream = if (status >= 400) connection.errorStream else connection.inputStream
    return status to stream.readBytes().toString(Charsets.UTF_8)
}

private fun get(port: Int, path: String): Pair<Int, String> {
    val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    val status = connection.responseCode
    val stream = if (status >= 400) connection.errorStream else connection.inputStream
    return status to stream.readBytes().toString(Charsets.UTF_8)
}

private fun toe(): LeanProject = LeanProject("toe", "Omega", "/srv/lean/omega", "ToE")

/** A fake audit-vault deployment: records what it was sent, answers whatever the test wants. */
private class FakeVault(
    private val onPost: (String, Map<String, String>, String) -> VaultTransport.Reply =
        { _, _, _ -> VaultTransport.Reply(200, "{}") },
    private val onGet: (String) -> VaultTransport.Reply = { VaultTransport.Reply(404, "") }
) : VaultTransport {
    val posts = mutableListOf<Triple<String, Map<String, String>, String>>()
    var gets = 0
        private set

    override fun get(path: String): VaultTransport.Reply {
        gets++
        return onGet(path)
    }

    override fun post(path: String, headers: Map<String, String>, body: String): VaultTransport.Reply {
        posts += Triple(path, headers, body)
        return onPost(path, headers, body)
    }
}

private fun vaultReply(
    job: LeanJobSpec,
    exit: Int = 0,
    errors: List<String> = emptyList(),
    attest: Boolean = true,
    lines: Int = 1
): String {
    val attestation = if (attest) {
        job.source?.let { AuditVaultContract.sha256(it) } ?: "none"
    } else {
        "0000000000000000000000000000000000000000000000000000000000000000"
    }
    return JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "contract" to 1,
            "job" to job.id,
            "ok" to (exit == 0 && errors.isEmpty()),
            "exit" to exit,
            "output" to (1..lines).map { "line $it" },
            "errors" to errors,
            "sorry_count" to 0,
            "axioms" to emptyList<String>(),
            "elapsed_ms" to 1_234,
            "attestation" to linkedMapOf<String, Any?>(
                "source_sha256" to attestation,
                "host" to "vault-1"
            )
        )
    )
}
