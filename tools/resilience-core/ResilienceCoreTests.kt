// Host-side verification harness for Lucifer's pure resilience core.
//
// Not part of the Android build. Compile and run with scripts/verify_resilience_core.sh.
package com.lucifer.mobile.resilience.harness

import com.lucifer.mobile.resilience.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

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

private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what: expected <$expected> but was <$actual>")
}

/** Runs a suspend block whose fakes never actually suspend. */
private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    val settled = result ?: throw IllegalStateException("test coroutine suspended; fakes must not suspend")
    return settled.getOrThrow()
}

private fun outcome(
    command: String,
    lines: List<String>,
    exit: Int? = null,
    target: ResilienceTarget = ResilienceTarget.VM
) = CommandOutcome(target = target, command = command, lines = lines, exitCode = exit)

// ─────────────────────────── fakes ───────────────────────────

private class FakeFetcher(private val pages: Map<String, FetchedPage>) : PageFetcher {
    val requests = mutableListOf<String>()
    override suspend fun fetch(url: String): FetchedPage {
        requests.add(url)
        pages[url]?.let { return it }
        // Prefix entries let a test answer any search query URL.
        val prefix = pages.entries.firstOrNull { (key, _) -> key.endsWith("*") && url.startsWith(key.dropLast(1)) }
        if (prefix != null) return prefix.value.copy(url = url)
        return FetchedPage(url, 404, "", error = "HTTP 404")
    }
}

private class FakeThinker(private val reply: String) : RecoveryThinker {
    var lastSystem = ""
    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Double): String {
        lastSystem = system
        return reply
    }
}

private fun signal(
    failureClass: FailureClass = FailureClass.MISSING_DEPENDENCY,
    key: String = "sig-1",
    target: ResilienceTarget = ResilienceTarget.VM,
    tool: String = "python3",
    errorLine: String = "ModuleNotFoundError: No module named 'requests'"
) = FailureSignal(
    failureClass = failureClass,
    key = key,
    tool = tool,
    errorLine = errorLine,
    target = target,
    command = "$tool script.py",
    exitCode = 1
)

fun main() {
    println("Lucifer resilience core · verification harness")

    // ── classification ────────────────────────────────────────────────
    test("classifies command-not-found") {
        val classified = FailureClassifier.classify(outcome("pytest", listOf("pytest: command not found"), 127))
        eq(classified?.failureClass, FailureClass.COMMAND_NOT_FOUND, "class")
    }

    test("classifies missing python dependency over path-missing") {
        val classified = FailureClassifier.classify(
            outcome("python3 app.py", listOf("Traceback (most recent call last):", "ModuleNotFoundError: No module named 'requests'"))
        )
        eq(classified?.failureClass, FailureClass.MISSING_DEPENDENCY, "class")
    }

    test("classifies permission denied") {
        val classified = FailureClassifier.classify(outcome("rm /data/x", listOf("rm: /data/x: Permission denied")))
        eq(classified?.failureClass, FailureClass.PERMISSION_DENIED, "class")
    }

    test("classifies DNS separately from generic network") {
        val classified = FailureClassifier.classify(outcome("curl https://example.com", listOf("curl: (6) Could not resolve host: example.com")))
        eq(classified?.failureClass, FailureClass.DNS, "class")
    }

    test("classifies exit code without text") {
        val classified = FailureClassifier.classify(outcome("weird-tool", listOf(""), 137))
        eq(classified?.failureClass, FailureClass.RESOURCE_LIMIT, "class")
    }

    test("successful output is not classified as failure") {
        eq(FailureClassifier.classify(outcome("ls", listOf("file.txt", "[exit 0]"), 0)), null, "signal")
    }

    test("stderr-style error with exit 0 still counts") {
        val classified = FailureClassifier.classify(outcome("build.sh", listOf("error: cannot find module 'left-pad'"), 0))
        eq(classified?.failureClass, FailureClass.MISSING_DEPENDENCY, "class")
    }

    test("signature is stable across volatile tokens") {
        val a = FailureClassifier.classify(outcome("pip install foo", listOf("ERROR: no space left on device (path /data/a/1)"), 1))
        val b = FailureClassifier.classify(outcome("pip install foo", listOf("ERROR: no space left on device (path /data/b/9)"), 1))
        expect(a != null && b != null, "both classified")
        eq(a!!.key, b!!.key, "signature key")
    }

    test("redaction removes secrets but keeps message") {
        val raw = "Authorization: Bearer abcdef1234567890 token=supersecret API_KEY=zzz for user@example.com"
        val clean = ResilienceRedactor.redact(raw)
        expect(!clean.contains("supersecret"), "token gone: $clean")
        expect(!clean.contains("abcdef1234567890"), "bearer gone: $clean")
        expect(!clean.contains("user@example.com"), "email gone: $clean")
        expect(clean.contains("Authorization"), "label kept")
    }

    // ── JSON ─────────────────────────────────────────────────────────
    test("JsonLite round-trips nested structures") {
        val value = linkedMapOf<String, Any?>(
            "a" to 1L,
            "b" to listOf("x", "y\n\"quoted\""),
            "c" to linkedMapOf("d" to true, "e" to null)
        )
        val parsed = JsonLite.parse(JsonLite.stringify(value)) as Map<*, *>
        eq((parsed["b"] as List<*>).size, 2, "array size")
        eq((parsed["c"] as Map<*, *>)["d"], true, "nested bool")
    }

    test("JsonLite tolerates prose around the object") {
        val parsed = JsonLite.parseObject("here you go: {\"root_cause\":\"disk\"} thanks")
        eq(parsed?.str("root_cause"), "disk", "root cause")
    }

    test("JsonLite returns null on malformed input") {
        eq(JsonLite.parse("{\"a\": "), null, "malformed")
    }

    test("JsonLite handles unicode escapes") {
        val parsed = JsonLite.parse("{\"t\":\"caf\\u00e9\"}") as Map<*, *>
        eq(parsed["t"], "café", "unicode")
    }

    // ── ledger ───────────────────────────────────────────────────────
    test("ledger ranks the tactic that has actually worked") {
        val ledger = LearningLedger()
        val now = 1_000_000L
        repeat(3) { ledger.record(Lesson("sig", FailureClass.MISSING_DEPENDENCY, RecoveryKind.FIX_DEPENDENCY, ResilienceTarget.VM, "python3", true, "installed", now, "e1")) }
        repeat(3) { ledger.record(Lesson("sig", FailureClass.MISSING_DEPENDENCY, RecoveryKind.WEB_RESEARCH, ResilienceTarget.VM, "python3", false, "docs unclear", now, "e1")) }
        val ranked = ledger.rank("sig", listOf(RecoveryKind.FIX_DEPENDENCY, RecoveryKind.WEB_RESEARCH), now)
        eq(ranked.first().kind, RecoveryKind.FIX_DEPENDENCY, "best tactic")
        expect(ranked.first().stat.successes == 3, "successes tracked")
    }

    test("ledger gives untouched tactics a neutral score") {
        val ledger = LearningLedger()
        val ranked = ledger.rank("nope", listOf(RecoveryKind.ASK_USER), 0L)
        eq(ranked.single().stat.attempts, 0, "attempts")
        expect(ranked.single().score in 0.2..0.8, "neutral score was ${ranked.single().score}")
    }

    test("ledger caps total and per-signature lessons") {
        val ledger = LearningLedger()
        repeat(LearningLedger.MAX_PER_SIGNATURE + 25) {
            ledger.record(Lesson("s", FailureClass.UNKNOWN, RecoveryKind.DIAGNOSE_ENVIRONMENT, ResilienceTarget.VM, "x", false, "n", 1L, "e"))
        }
        expect(ledger.size <= LearningLedger.MAX_PER_SIGNATURE, "per signature cap, was ${ledger.size}")
    }

    test("ledger round-trips through JSON") {
        val ledger = LearningLedger()
        ledger.record(Lesson("sig-a", FailureClass.NETWORK, RecoveryKind.WAIT_AND_RETRY, ResilienceTarget.TERMUX, "curl", true, "retry worked", 42L, "ep"))
        val decoded = LearningLedger.decode(ledger.encode())
        expect(decoded != null, "decoded")
        eq(decoded!!.lessons().size, 1, "lesson count")
        eq(decoded.lessons().first().kind, RecoveryKind.WAIT_AND_RETRY, "kind")
    }

    test("ledger refuses to decode an unknown schema") {
        eq(LearningLedger.decode("{\"schema\":99,\"lessons\":[]}"), null, "decoded")
        eq(LearningLedger.decode("not json"), null, "garbage")
    }

    test("ledger can forget a signature") {
        val ledger = LearningLedger()
        ledger.record(Lesson("k1", FailureClass.UNKNOWN, RecoveryKind.ASK_USER, ResilienceTarget.VM, "t", true, "n", 1L))
        ledger.record(Lesson("k2", FailureClass.UNKNOWN, RecoveryKind.ASK_USER, ResilienceTarget.VM, "t", true, "n", 1L))
        eq(ledger.forget("k1"), 1, "removed")
        eq(ledger.size, 1, "remaining")
    }


    // ── intent detection ─────────────────────────────────────────────
    test("detects stuck/investigate phrasing") {
        listOf(
            "why did that fail?",
            "I'm stuck, the build keeps failing",
            "run the vm and figure out what's going on",
            "investigate this",
            "debug this",
            "diagnose it"
        ).forEach { phrase ->
            eq(ResilienceIntents.detect(phrase)?.kind, ResilienceIntentKind.INVESTIGATE, "phrase: $phrase")
        }
    }

    test("detects learning and planning requests") {
        eq(ResilienceIntents.detect("what have you learned so far")?.kind, ResilienceIntentKind.LESSONS, "lessons")
        eq(ResilienceIntents.detect("give me options")?.kind, ResilienceIntentKind.PLAN, "plan")
        eq(ResilienceIntents.detect("what should we try next")?.kind, ResilienceIntentKind.PLAN, "plan 2")
    }

    test("detects web research and strips the trigger words") {
        val research = ResilienceIntents.detect("research the exact pip error for me")
        eq(research?.kind, ResilienceIntentKind.RESEARCH, "research")
        expect(research!!.query.contains("pip"), "query was ${research.query}")
        eq(ResilienceIntents.detect("look it up")?.query, "", "bare look-it-up has empty query")
        eq(ResilienceIntents.detect("search the web for kotlin coroutines")?.query, "kotlin coroutines", "query stripped")
    }

    test("does not hijack ordinary commands or app requests") {
        listOf(
            "install python requests",
            "open Chrome",
            "search my files for config",
            "df -h",
            "what is entropy?",
            "research"
        ).forEach { phrase ->
            val detected = ResilienceIntents.detect(phrase)
            if (phrase == "research") {
                eq(detected?.kind, ResilienceIntentKind.RESEARCH, "bare research is a request")
            } else {
                eq(detected, null, "phrase: $phrase")
            }
        }
    }

    // ── playbook ─────────────────────────────────────────────────────
    test("every failure class has a playbook with distinct tactics") {
        for (failureClass in FailureClass.entries) {
            val moves = RecoveryPlaybook.candidates(signal(failureClass = failureClass))
            expect(moves.isNotEmpty(), "empty playbook for $failureClass")
            eq(moves.map { it.kind }.distinct().size, moves.size, "duplicate tactic in $failureClass")
        }
    }

    test("playbook offers a read-only first move for most classes") {
        val classes = FailureClass.entries.filter { it != FailureClass.NONE }
        val withReadOnly = classes.count { c ->
            RecoveryPlaybook.candidates(signal(failureClass = c)).any { it.isReadOnly }
        }
        expect(withReadOnly >= classes.size - 1, "only $withReadOnly/${classes.size} classes start read-only")
    }

    // ── probes ───────────────────────────────────────────────────────
    test("probe catalog ids are unique and target-correct") {
        val ids = DiagnosticCatalog.all.map { it.id }
        eq(ids.distinct().size, ids.size, "unique ids")
        for (probe in DiagnosticCatalog.all) {
            expect(probe.command.isNotBlank(), "empty command for ${probe.id}")
            expect(DiagnosticCatalog.isReadOnlyCommand(probe.command), "probe not self-whitelisted: ${probe.id}")
        }
    }

    test("read-only check rejects invented commands") {
        expect(!DiagnosticCatalog.isReadOnlyCommand("rm -rf /"), "blocked rm")
        expect(!DiagnosticCatalog.isReadOnlyCommand("df -h; rm -rf /data"), "blocked chained")
    }

    test("probe selection matches the failure and the target") {
        val probes = DiagnosticCatalog.forSignal(signal(failureClass = FailureClass.DNS, target = ResilienceTarget.VM))
        expect(probes.isNotEmpty(), "probes")
        expect(probes.all { it.target == ResilienceTarget.VM }, "target respected")
        expect(probes.any { it.category == "dns" }, "dns probe chosen")
    }

    test("evidence highlights detect missing tools and DNS") {
        val sig = signal(failureClass = FailureClass.MISSING_DEPENDENCY)
        val bundle = EvidenceBundle(sig, listOf(
            ProbeResult(DiagnosticCatalog.byId("vm-tools")!!, listOf("python3=/usr/bin/python3", "node=missing"), 0),
            ProbeResult(DiagnosticCatalog.byId("vm-dns")!!, listOf("dns-failed"), 0)
        ))
        val highlights = bundle.highlights().joinToString(" ")
        expect(highlights.contains("missing", ignoreCase = true), "missing tool noted: $highlights")
        expect(highlights.contains("DNS", ignoreCase = true), "dns noted: $highlights")
    }

    // ── diagnosis ────────────────────────────────────────────────────
    test("diagnosis falls back to heuristics with no model") {
        val sig = signal(failureClass = FailureClass.DISK_FULL)
        val bundle = EvidenceBundle(sig, listOf(
            ProbeResult(DiagnosticCatalog.byId("vm-disk")!!, listOf("Filesystem Size Used Avail Use% Mounted on", "/dev/sda1 20G 20G 0 100% /"), 0)
        ))
        val diagnosis = runSuspend { DiagnosisEngine(null).diagnose(bundle) }
        eq(diagnosis.source, DiagnosisSource.HEURISTIC, "source")
        expect(diagnosis.rootCause.contains("full", ignoreCase = true), "root cause was ${diagnosis.rootCause}")
        expect(diagnosis.nextMoves.isNotEmpty(), "moves")
    }

    test("diagnosis parses a model reply and clamps to known tactics") {
        val reply = """
            {"root_cause":"The VM resolves no hostnames","confidence":"medium","evidence":["vm-dns"],
             "hypotheses":[{"statement":"resolv.conf is stale","confidence":0.6,"check":"cat /etc/resolv.conf"}],
             "next_moves":[{"kind":"DIAGNOSE_ENVIRONMENT","title":"Recheck DNS","instruction":"Re-run DNS probe","rationale":"cheap"},
                           {"kind":"DELETE_EVERYTHING","title":"nuke","instruction":"wipe"}]}
        """.trimIndent()
        val diagnosis = runSuspend { DiagnosisEngine(FakeThinker(reply)).diagnose(EvidenceBundle(signal(), emptyList())) }
        eq(diagnosis.source, DiagnosisSource.MODEL, "source")
        eq(diagnosis.nextMoves.size, 1, "unknown tactic dropped")
        eq(diagnosis.nextMoves.first().kind, RecoveryKind.DIAGNOSE_ENVIRONMENT, "kind")
        eq(diagnosis.hypotheses.first().confidence, 0.6, "confidence")
    }

    test("diagnosis rejects a model move that claims unsafe permissions") {
        val reply = """{"root_cause":"needs root","confidence":"low","next_moves":[
            {"kind":"RECONFIGURE","title":"fix perms","instruction":"chmod 777 everything"}]}"""
        val diagnosis = runSuspend { DiagnosisEngine(FakeThinker(reply)).diagnose(EvidenceBundle(signal(), emptyList())) }
        eq(diagnosis.nextMoves.first().safety, MoveSafety.REQUIRES_CONFIRMATION, "safety derived from tactic")
    }

    // ── robots + search parsing ──────────────────────────────────────
    test("robots disallow is honored with longest-match allow") {
        val rules = RobotsRules.parse("""
            User-agent: *
            Disallow: /private
            Allow: /private/ok
            Crawl-delay: 2
        """.trimIndent())
        expect(!rules.isAllowed("/private/secret"), "disallowed")
        expect(rules.isAllowed("/private/ok/page"), "allowed override")
        expect(rules.isAllowed("/public"), "unlisted allowed")
        eq(rules.crawlDelaySeconds, 2, "crawl delay")
    }

    test("robots agent-specific group wins") {
        val rules = RobotsRules.parse("""
            User-agent: *
            Disallow: /
            User-agent: lucifer
            Disallow: /nope
        """.trimIndent())
        expect(rules.isAllowed("/anything"), "lucifer group used")
        expect(!rules.isAllowed("/nope"), "lucifer disallow")
    }

    test("search parser extracts and decodes DuckDuckGo redirects") {
        val html = """
            <div class="result"><a rel="nofollow" class="result__a"
              href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.python.org%2F3%2Finstalling%2F&amp;rut=abc">Python Setup</a>
              <a class="result__snippet">How to install Python packages safely.</a></div>
            <div class="result"><a rel="nofollow" class="result__a" href="https://nodejs.org/en/download">Node Downloads</a></div>
        """.trimIndent()
        val hits = SearchResultParser.parse(html)
        eq(hits.size, 2, "hits")
        eq(hits.first().url, "https://docs.python.org/3/installing/", "decoded url")
        eq(hits.first().title, "Python Setup", "title")
        expect(hits.first().snippet.contains("install"), "snippet parsed")
    }

    // ── research agent ───────────────────────────────────────────────
    val searchPage = FetchedPage(
        "https://html.duckduckgo.com/html/?q=x", 200,
        """<a class="result__a" href="https://docs.python.org/3/installing/">Installing Packages</a>
           <a class="result__snippet">Use pip install with a requirements file.</a>
           <a class="result__a" href="https://example.com/blog">A blog</a>"""
    )
    val docsPage = FetchedPage(
        "https://docs.python.org/3/installing/", 200,
        "<html><body><h1>Installing Packages</h1><p>Use python3 -m pip install to install packages. " +
            "If pip is missing, bootstrap it with ensurepip.</p><script>bad()</script></body></html>"
    )

    test("research agent obeys robots and reports skipped pages") {
        val fetcher = FakeFetcher(
            mapOf(
                "https://html.duckduckgo.com/robots.txt" to FetchedPage("robots", 200, "User-agent: *\nDisallow: /html"),
                "https://html.duckduckgo.com/html/?*" to searchPage
            )
        )
        val brief = runSuspend { ResearchAgent(fetcher).research(ResearchAgent.Request("ModuleNotFoundError pip missing")) }
        eq(brief.sources.size, 0, "nothing fetched")
        expect(brief.notes.any { it.contains("robots", ignoreCase = true) }, "robots note: ${brief.notes}")
        expect(fetcher.requests.none { it.contains("/html?") }, "disallowed URL not requested")
    }

    test("research agent collects cited sources within budget") {
        val fetcher = FakeFetcher(
            mapOf(
                "https://html.duckduckgo.com/robots.txt" to FetchedPage("robots", 200, "User-agent: *\nAllow: /"),
                "https://html.duckduckgo.com/html/?*" to searchPage,
                "https://docs.python.org/robots.txt" to FetchedPage("robots", 200, ""),
                "https://example.com/robots.txt" to FetchedPage("robots", 200, ""),
                "https://docs.python.org/3/installing/" to docsPage,
                "https://example.com/blog" to FetchedPage("https://example.com/blog", 200, "<p>Nothing useful.</p>")
            )
        )
        val brief = runSuspend {
            ResearchAgent(fetcher, maxPages = 6).research(
                ResearchAgent.Request("how do I install python packages with pip", errorText = "ModuleNotFoundError pip missing")
            )
        }
        expect(brief.sources.isNotEmpty(), "sources: ${brief.notes}")
        expect(brief.sources.first().id.startsWith("S"), "citation id")
        expect(brief.sources.any { it.host == "docs.python.org" }, "docs host present")
        expect(brief.pagesFetched <= 6, "page budget respected (${brief.pagesFetched})")
        val prompt = brief.toPromptContext()
        expect(prompt.contains("UNTRUSTED"), "prompt labeled untrusted")
        expect(prompt.contains("[S1]"), "citation in prompt")
        expect(brief.render().contains("Research:"), "render")
    }

    test("research agent de-duplicates identical urls") {
        val dupes = searchPage.copy(body = """
            <a class="result__a" href="https://example.com/a">A</a>
            <a class="result__a" href="https://example.com/a/">A again</a>
            <a class="result__a" href="https://example.com/a#x">A anchor</a>
        """.trimIndent())
        val fetcher = FakeFetcher(
            mapOf(
                "https://html.duckduckgo.com/robots.txt" to FetchedPage("r", 200, ""),
                "https://html.duckduckgo.com/html/?*" to dupes,
                "https://example.com/robots.txt" to FetchedPage("r", 200, ""),
                "https://example.com/a" to FetchedPage("https://example.com/a", 200, "<p>page a</p>"),
                "https://example.com/a/" to FetchedPage("https://example.com/a/", 200, "<p>page a</p>")
            )
        )
        val brief = runSuspend { ResearchAgent(fetcher).research(ResearchAgent.Request("example dupes")) }
        eq(brief.sources.count { it.host == "example.com" }, 1, "deduped: ${brief.sources.map { it.url }}")
    }

    test("research query planning strips filler and adds error focus") {
        val agent = ResearchAgent(FakeFetcher(emptyMap()))
        val queries = agent.planQueries(ResearchAgent.Request(
            "Could you please figure out why the build fails with this error",
            errorText = "error TS2307: Cannot find module 'left-pad'"
        ))
        expect(queries.isNotEmpty() && queries.size <= 3, "query count ${queries.size}")
        expect(queries.first().contains("build"), "question kept: ${queries.first()}")
        expect(queries.any { it.contains("TS2307") || it.contains("left-pad") }, "error query: $queries")
    }

    // ── planner ──────────────────────────────────────────────────────
    test("planner excludes tactics that already failed and keeps read-only first") {
        val ledger = LearningLedger()
        val planner = RecoveryPlanner(ledger)
        val episode = planner.startEpisode(signal())
        val moves = planner.planMoves(episode, limit = 4)
        expect(moves.isNotEmpty(), "moves")
        val after = planner.recordAttempt(episode.copy(plan = moves), moves.first(), false, "did not help")
        val replanned = planner.planMoves(after, limit = 4)
        expect(replanned.none { it.kind == moves.first().kind }, "failed tactic dropped")
    }

    test("learned successes get promoted into the plan") {
        val ledger = LearningLedger()
        val now = System.currentTimeMillis()
        repeat(4) {
            ledger.record(Lesson("sig-1", FailureClass.MISSING_DEPENDENCY, RecoveryKind.ALTERNATE_TOOL,
                ResilienceTarget.VM, "python3", true, "stdlib worked", now, "old"))
        }
        val planner = RecoveryPlanner(ledger)
        val moves = planner.planMoves(planner.startEpisode(signal()), limit = 4, now = now)
        expect(moves.any { it.kind == RecoveryKind.ALTERNATE_TOOL && it.origin == MoveOrigin.LEARNED },
            "learned tactic promoted: ${moves.map { it.kind to it.origin }}")
    }

    test("recording a success resolves the episode and writes one lesson") {
        val ledger = LearningLedger()
        val planner = RecoveryPlanner(ledger)
        val episode = planner.startEpisode(signal())
        val move = RecoveryPlaybook.candidates(episode.signal).first()
        val after = planner.recordAttempt(episode, move, true, "worked after reinstall")
        eq(after.phase, RecoveryPhase.RESOLVED, "phase")
        eq(ledger.size, 1, "lesson written")
        expect(after.summaryLine.contains("resolved"), "summary was ${after.summaryLine}")
    }

    test("repeated failures exhaust the loop at the attempt cap") {
        val ledger = LearningLedger()
        val planner = RecoveryPlanner(ledger)
        var episode = planner.startEpisode(signal())
        val kinds = RecoveryKind.entries
        for (index in 0 until RecoveryEpisode.MAX_ATTEMPTS) {
            episode = planner.recordAttempt(episode, RecoveryMove(kinds[index], "t", "r", "i"), false, "no effect")
        }
        eq(episode.phase, RecoveryPhase.EXHAUSTED, "phase")
        eq(episode.attempts.size, RecoveryEpisode.MAX_ATTEMPTS, "attempts")
    }

    test("full stuck loop: diagnose, plan, learn, replan") {
        val ledger = LearningLedger()
        val planner = RecoveryPlanner(ledger)
        val sig = signal(
            failureClass = FailureClass.COMMAND_NOT_FOUND,
            key = "stuck-1",
            tool = "pytest",
            errorLine = "pytest: command not found"
        )
        var episode = planner.startEpisode(sig)
        val bundle = EvidenceBundle(sig, listOf(
            ProbeResult(DiagnosticCatalog.byId("vm-tools")!!, listOf("python3=/usr/bin/python3", "pytest=missing"), 0)
        ))
        val diagnosis = runSuspend { DiagnosisEngine(null).diagnose(bundle) }
        episode = planner.withDiagnosis(episode, diagnosis)
        val coach = RecoveryCoach(null, planner)
        val plan = runSuspend { coach.plan(episode, bundle) }
        expect(plan.isNotEmpty(), "plan")
        episode = planner.withPlan(episode, plan)
        episode = planner.recordAttempt(episode, plan.first(), false, "still missing")
        val nextPlan = runSuspend { coach.plan(episode, bundle) }
        expect(nextPlan.none { it.kind == plan.first().kind }, "no repeat after failure")
        expect(ledger.size == 1, "lesson recorded")
        val described = planner.describe(episode)
        expect(described.contains("Learned"), "describe includes learning: $described")
    }

    test("coach prefers model moves when one is reachable") {
        val ledger = LearningLedger()
        val planner = RecoveryPlanner(ledger)
        val reply = """{"moves":[{"kind":"ALTERNATE_TARGET","title":"Try Termux","instruction":"Run pytest in Termux","rationale":"Termux has pytest"}]}"""
        val coach = RecoveryCoach(FakeThinker(reply), planner)
        val plan = runSuspend { coach.plan(planner.startEpisode(signal(failureClass = FailureClass.COMMAND_NOT_FOUND))) }
        eq(plan.first().kind, RecoveryKind.ALTERNATE_TARGET, "model first")
        eq(plan.first().origin, MoveOrigin.MODEL, "origin")
    }

    test("planner hands a failing episode back to the user when out of tactics") {
        val ledger = LearningLedger()
        val planner = RecoveryPlanner(ledger)
        var episode = planner.startEpisode(signal())
        // Fail every single tactic the playbook and fallbacks know about.
        for (kind in RecoveryKind.entries) {
            episode = planner.recordAttempt(episode, RecoveryMove(kind, "t", "r", "i"), false, "nope")
        }
        val moves = planner.planMoves(episode, limit = 4)
        expect(moves.isEmpty(), "nothing left: ${moves.map { it.kind }}")
        val moved = planner.withPlan(episode, moves)
        eq(moved.phase, RecoveryPhase.EXHAUSTED, "phase")
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
