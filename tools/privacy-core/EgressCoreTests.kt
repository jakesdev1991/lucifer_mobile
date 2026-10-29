// Host-side verification for the egress ledger.
//
// `EgressLedger.kt` is the rule that decides what leaves the phone. It is deliberately free of
// Android, so it can be compiled and executed on a JVM with nothing but kotlinc — which is the only
// way a privacy claim gets to be a fact instead of a comment.
//
// Not part of the Android build. Run with scripts/verify_privacy_core.sh.
package com.lucifer.mobile.privacy.harness

import com.lucifer.mobile.privacy.EgressClass
import com.lucifer.mobile.privacy.EgressCodec
import com.lucifer.mobile.privacy.EgressLog
import com.lucifer.mobile.privacy.EgressOutcome
import com.lucifer.mobile.privacy.EgressPolicy
import com.lucifer.mobile.privacy.EgressRecord
import com.lucifer.mobile.privacy.PlayClassifier
import com.lucifer.mobile.privacy.PlayFacts
import com.lucifer.mobile.privacy.PlayFlavor

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

private fun expectEq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what: expected <$expected> but was <$actual>")
}

/** The destinations this repository actually contacts, so the rule is tested against reality. */
private val REAL_DESTINATIONS = listOf(
    "https://integrate.api.nvidia.com/v1/chat/completions",
    "https://html.duckduckgo.com/html/?q=x",
    "https://github.com/trending?since=daily",
    "https://news.ycombinator.com/",
    "https://ethereum-rpc.publicnode.com",
    "https://api.github.com/git/trees/main?recursive=1"
)

fun main() {
    println("Egress ledger · verification harness")

    test("parse: host and path only") {
        expectEq(
            EgressPolicy.parse("https://integrate.api.nvidia.com/v1/chat/completions"),
            "integrate.api.nvidia.com" to "/v1/chat/completions",
            "normal https"
        )
        expectEq(EgressPolicy.parse("https://example.com"), "example.com" to "/", "no path")
        expectEq(EgressPolicy.parse("http://127.0.0.1:8030/health"), "127.0.0.1" to "/health", "port stripped")
        expectEq(EgressPolicy.parse("https://[::1]:8030/x"), "[::1]" to "/x", "ipv6 literal")
        expectEq(EgressPolicy.parse("https://user:pw@evil.example/x"), "evil.example" to "/x", "credentials dropped")
        expectEq(
            EgressPolicy.parse("https://html.duckduckgo.com/html/?q=how+to+hide"),
            "html.duckduckgo.com" to "/html/",
            "query stripped from route"
        )
        expectEq(EgressPolicy.parse("https://a.example/p#frag"), "a.example" to "/p", "fragment stripped")
    }

    test("parse: refuses anything without an identifiable host") {
        expect(EgressPolicy.parse("integrate.api.nvidia.com/v1") == null, "no scheme")
        expect(EgressPolicy.parse("ftp://example.com/x") == null, "ftp")
        expect(EgressPolicy.parse("https://") == null, "bare scheme")
        expect(EgressPolicy.parse("") == null, "empty")
        expect(EgressPolicy.parse("https://intranet/x") == null, "single-label host")
        expect(EgressPolicy.parse("javascript:alert(1)") == null, "javascript")
        expect(EgressPolicy.parse("https://" + "a".repeat(5000) + ".com/") == null, "absurd length")
    }

    test("parse: long paths truncate instead of vanishing") {
        val route = EgressPolicy.parse("https://a.example/" + "x".repeat(200))?.second ?: ""
        expect(route.length == EgressPolicy.MAX_ROUTE_CHARS, "length clamped: ${route.length}")
        expect(route.endsWith("…"), "truncation is marked")
    }

    test("classify: known destinations") {
        expectEq(EgressPolicy.classify("integrate.api.nvidia.com"), EgressClass.MODEL, "nvidia")
        expectEq(EgressPolicy.classify("html.duckduckgo.com"), EgressClass.SEARCH, "duckduckgo")
        expectEq(EgressPolicy.classify("x.duckduckgo.com"), EgressClass.SEARCH, "ddg subdomain")
        expectEq(EgressPolicy.classify("raw.githubusercontent.com"), EgressClass.CODE, "github raw")
        expectEq(EgressPolicy.classify("ethereum-rpc.publicnode.com"), EgressClass.RPC, "rpc node")
        expectEq(EgressPolicy.classify("arb1.arbitrum.io"), EgressClass.RPC, "arbitrum")
        expectEq(EgressPolicy.classify("news.ycombinator.com"), EgressClass.PAGE, "hacker news")
        expectEq(EgressPolicy.classify("127.0.0.1"), EgressClass.LOCAL, "loopback")
    }

    test("classify: a look-alike host cannot borrow a rule") {
        expectEq(EgressPolicy.classify("duckduckgo.com.evil.example"), EgressClass.PAGE, "suffix spoof")
        expectEq(EgressPolicy.classify("evil-duckduckgo.com"), EgressClass.PAGE, "prefix spoof")
        expectEq(EgressPolicy.classify("nvidia.com.attacker.example"), EgressClass.PAGE, "nvidia spoof")
    }

    test("local: what stays on the device") {
        for (host in listOf(
            "127.0.0.1", "localhost", "10.0.2.2", "10.1.2.3", "172.16.0.1",
            "172.31.255.255", "192.168.1.20", "169.254.1.1", "::1", "[::1]",
            "printer.local", "svc.internal"
        )) {
            expect(EgressPolicy.isLocal(host), "should be local: $host")
        }
        for (host in listOf("172.32.0.1", "8.8.8.8", "example.com", "192.169.0.1")) {
            expect(!EgressPolicy.isLocal(host), "should not be local: $host")
        }
    }

    test("decide: online model call is allowed and classified") {
        val decision = EgressPolicy.decide("https://integrate.api.nvidia.com/v1/chat", online = true, userInitiated = true)
        expect(decision.allowed, "allowed")
        expectEq(decision.kind, EgressClass.MODEL, "kind")
        expectEq(decision.outcome, EgressOutcome.ALLOWED, "outcome")
    }

    test("decide: switch off blocks every remote destination") {
        for (url in REAL_DESTINATIONS) {
            val decision = EgressPolicy.decide(url, online = false, userInitiated = true)
            expect(!decision.allowed, "should be blocked: $url")
            expectEq(decision.outcome, EgressOutcome.BLOCKED_OFFLINE, url)
        }
    }

    test("decide: switch off does not break the device's own services") {
        for (url in listOf(
            "http://127.0.0.1:8030/health",
            "http://10.0.2.2:8030/health",
            "http://192.168.1.20:8765/",
            "http://localhost:3845/mcp"
        )) {
            val decision = EgressPolicy.decide(url, online = false, userInitiated = true)
            expect(decision.allowed, "should survive: $url")
            expectEq(decision.kind, EgressClass.LOCAL, url)
        }
    }

    test("decide: an unidentifiable destination is refused even online") {
        expect(!EgressPolicy.decide("notaurl", online = true, userInitiated = true).allowed, "notaurl")
        expectEq(
            EgressPolicy.decide("notaurl", online = true, userInitiated = true).outcome,
            EgressOutcome.BLOCKED_POLICY,
            "outcome"
        )
    }

    test("decide: a caller can declare that an image left the device") {
        val decision = EgressPolicy.decide(
            "https://integrate.api.nvidia.com/v1/chat/completions",
            online = true,
            userInitiated = true,
            expected = EgressClass.VISION
        )
        expectEq(decision.kind, EgressClass.VISION, "vision overrides model")
    }

    test("ledger: capacity is a ring, not a growth curve") {
        val log = EgressLog(capacity = 3)
        for (index in 0 until 5) log.record(record(epoch = index.toLong()))
        expectEq(log.size(), 3, "size")
        expectEq(log.snapshot().map { it.epochMillis }, listOf(2L, 3L, 4L), "oldest dropped")
        val single = EgressLog(capacity = 1)
        for (index in 0 until 100) single.record(record(epoch = index.toLong()))
        expectEq(single.size(), 1, "capacity 1")
    }

    test("ledger: blocked attempts are counted separately") {
        val log = EgressLog()
        expectEq(log.summary(), "No outbound requests recorded.", "empty summary")
        log.record(record(kind = EgressClass.MODEL))
        log.record(record(kind = EgressClass.SEARCH, outcome = EgressOutcome.BLOCKED_OFFLINE))
        expectEq(log.blockedCount(), 1, "blocked")
        expect(log.summary().startsWith("2 attempts · 1 allowed · 1 blocked"), "summary: ${log.summary()}")
        expect(log.summary().endsWith("MODEL 1 · SEARCH 1"), "class order: ${log.summary()}")
    }

    test("redaction: a search query never reaches the ledger") {
        val url = "https://html.duckduckgo.com/html/?q=my+private+search"
        val (host, route) = EgressPolicy.parse(url) ?: throw AssertionError("parse failed")
        expectEq(host, "html.duckduckgo.com", "host")
        expect(!route.contains("?"), "no query in route: $route")
        expect(!route.contains("private"), "no query text in route: $route")
        expect(EgressPolicy.hasQueryString(url), "query presence is recorded instead")
    }

    test("codec: seven fields, round-trips, and never throws on junk") {
        val sample = record(epoch = 1_717_000_000_000L, kind = EgressClass.MODEL, host = "integrate.api.nvidia.com", route = "/v1/chat/completions")
        val line = EgressCodec.encode(sample)
        expectEq(line.split('\t').size, EgressCodec.FIELD_COUNT, "field count")
        expectEq(EgressCodec.decode(line), sample, "roundtrip")
        expect(EgressCodec.decode("only\tthree\tfields") == null, "corrupt row dropped")
        expect(EgressCodec.decode("x\th\t/\tPAGE\ttrue\tALLOWED\tfalse") == null, "bad epoch dropped")
        expect(EgressCodec.decode("") == null, "empty dropped")
        expectEq(EgressCodec.decode("1\th\t/\tNUKE\ttrue\tALLOWED\tfalse")?.kind, EgressClass.UNKNOWN, "unknown enum")
        expectEq(
            EgressCodec.decode("1\th\t/\tPAGE\ttrue\tNUKE\tfalse")?.outcome,
            EgressOutcome.BLOCKED_POLICY,
            "unknown outcome fails closed"
        )
    }

    test("codec: a tab inside a field cannot forge a row") {
        val hostile = record(route = "/a\tb\nc")
        val decoded = EgressCodec.decode(EgressCodec.encode(hostile)) ?: throw AssertionError("dropped")
        expectEq(decoded.route, "/a b c", "tab and newline collapsed")
        expectDecodes(hostile, "hostile route")
    }

    test("codec: one corrupt line does not stop the file") {
        val good = EgressCodec.encode(record())
        val lines = listOf(good, "garbage", good)
        val decoded = lines.mapNotNull { EgressCodec.decode(it) }
        expectEq(decoded.size, 2, "survivors")
        val log = EgressLog(capacity = 10)
        expectEq(log.loadLines(lines), 2, "loadLines count")
        expectEq(log.size(), 2, "log size")
    }

    test("ledger: file round-trip keeps the ring in order") {
        val log = EgressLog(capacity = 4)
        for (index in 0 until 6) log.record(record(epoch = index.toLong()))
        val reloaded = ArrayList<EgressRecord>()
        for (line in log.toLines()) EgressCodec.decode(line)?.let { reloaded.add(it) }
        expectEq(reloaded.map { it.epochMillis }, listOf(2L, 3L, 4L, 5L), "order preserved")
    }

    test("ledger: records carry what the panel prints") {
        val decision = EgressPolicy.decide("https://news.ycombinator.com/", online = true, userInitiated = false)
        val entry = EgressPolicy.record("https://news.ycombinator.com/", decision, 42L, userInitiated = false)
        expectEq(entry.host, "news.ycombinator.com", "host")
        expectEq(entry.route, "/", "route")
        expectEq(entry.userInitiated, false, "attribution")
        expectEq(entry.summary(), "PAGE news.ycombinator.com/", "summary line")
    }

    test("ledger: a blocked record says so") {
        val decision = EgressPolicy.decide("https://integrate.api.nvidia.com/v1", online = false, userInitiated = true)
        val entry = EgressPolicy.record("https://integrate.api.nvidia.com/v1", decision, 1L, true)
        expect(entry.summary().endsWith("· BLOCKED"), "summary marks the block: ${entry.summary()}")
    }

    test("play: stock Google Play Services is recognised as Google") {
        val facts = PlayFacts(
            gmsInstalled = true,
            gmsLabel = "Google Play services",
            gmsVersionName = "25.08.12",
            gmsInstaller = "com.android.vending",
            vendingInstalled = true,
            vendingLabel = "Google Play Store"
        )
        expectEq(PlayClassifier.classify(facts), PlayFlavor.GOOGLE, "flavor")
        expect(PlayClassifier.pushVerdict(PlayFlavor.GOOGLE).contains("Google"), "push names Google")
    }

    test("play: official microG is detected without trusting the package name") {
        // microG installs AS com.google.android.gms; only the label/version/installer give it away.
        val facts = PlayFacts(
            gmsInstalled = true,
            gmsLabel = "microG Services",
            gmsVersionName = "0.3.15.250932",
            gmsInstaller = "",
            vendingInstalled = true,
            vendingLabel = "microG Companion"
        )
        expectEq(PlayClassifier.classify(facts), PlayFlavor.MICROG, "flavor")
        expect(facts.microgSignals().size >= PlayClassifier.MICROG_SIGNAL_THRESHOLD, "signals")
    }

    test("play: microG does not buy silence from Google for push") {
        expect(
            PlayClassifier.pushVerdict(PlayFlavor.MICROG).contains("mtalk.google.com"),
            "the caveat is stated, not buried: ${PlayClassifier.pushVerdict(PlayFlavor.MICROG)}"
        )
        expect(
            PlayClassifier.integrityVerdict(PlayFlavor.MICROG).contains("not expected to pass"),
            "integrity is honest"
        )
    }

    test("play: forks and preview builds are named") {
        expectEq(PlayClassifier.classify(PlayFacts(revenancedGmsInstalled = true)), PlayFlavor.REVANCED_MICROG, "revanced")
        expectEq(PlayClassifier.classify(PlayFacts(microgPreviewInstalled = true)), PlayFlavor.MICROG, "preview")
        expectEq(PlayClassifier.classify(PlayFacts()), PlayFlavor.ABSENT, "absent")
        expect(
            PlayClassifier.pushVerdict(PlayFlavor.ABSENT).contains("Signal"),
            "no services means app-owned sockets"
        )
    }

    test("play: one weak signal is UNKNOWN, never a guess") {
        val weak = PlayFacts(
            gmsInstalled = true,
            gmsLabel = "Google Play services",
            gmsVersionName = "0.3.2",
            gmsInstaller = "com.android.vending"
        )
        expectEq(PlayClassifier.classify(weak), PlayFlavor.UNKNOWN, "flavor")
        expectEq(weak.microgSignals().size, 1, "one signal")
        expect(PlayClassifier.report(weak).contains("Signals:"), "evidence is shown")
    }

    test("play: a sideloaded Google build is not mistaken for microG") {
        val sideloaded = PlayFacts(
            gmsInstalled = true,
            gmsLabel = "Google Play services",
            gmsVersionName = "25.08.12",
            gmsInstaller = "com.android.packageinstaller"
        )
        expectEq(PlayClassifier.classify(sideloaded), PlayFlavor.UNKNOWN, "flavor")
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

private fun expectDecodes(record: EgressRecord, what: String) {
    if (EgressCodec.decode(EgressCodec.encode(record)) == null) throw AssertionError("$what did not decode")
}

private fun record(
    epoch: Long = 1L,
    kind: EgressClass = EgressClass.PAGE,
    host: String = "example.com",
    route: String = "/",
    outcome: EgressOutcome = EgressOutcome.ALLOWED,
    userInitiated: Boolean = true,
    hadQuery: Boolean = false
) = EgressRecord(epoch, host, route, kind, userInitiated, outcome, hadQuery)
