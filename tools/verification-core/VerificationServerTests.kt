// Host-side verification for the verifier's runtime and transport.
//
// The control plane (`VerificationCore`) is checked elsewhere. This file checks the parts that hold
// state and speak TCP: key persistence, meters that survive a restart, the charging rule, the command
// tripwire, and a real loopback HTTP exchange.
//
// Not part of the Android build. Run with scripts/verify_verification_core.sh.
package com.lucifer.mobile.verification.server.harness

import com.lucifer.mobile.verification.ApiKeys
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
