package com.lucifer.mobile.cognition.harness

import com.lucifer.mobile.cognition.CognitionLedger
import com.lucifer.mobile.cognition.CognitionOutcome
import com.lucifer.mobile.cognition.CognitionPlanner
import com.lucifer.mobile.cognition.CognitionRunner
import com.lucifer.mobile.cognition.CognitionToolbox
import com.lucifer.mobile.cognition.LoopBudget
import com.lucifer.mobile.cognition.SideEffect
import com.lucifer.mobile.cognition.ToolCall
import com.lucifer.mobile.cognition.ToolCatalogue
import com.lucifer.mobile.cognition.ToolResult
import com.lucifer.mobile.cognition.ToolSpec
import com.lucifer.mobile.cognition.WorkingMemory
import com.lucifer.mobile.cognition.AnswerAudit.Finding
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * The ledger, executed.
 *
 * Two promises are load-bearing and neither had been run: that a run leaves a record the owner can
 * read, and that the record **never contains what a tool returned**. The second is a privacy invariant
 * — a page Lucifer read must not be persisted as a copy of the web — so it is asserted against the file
 * itself, not against the code.
 *
 * The `org.json` implementation under the stubs is a real (small) one, because a ledger test that
 * cannot serialise is a ledger test that proves nothing.
 */

private var failures = 0
private var checks = 0

private fun test(name: String, body: () -> Unit) {
    checks++
    try {
        body()
        println("  ok    $name")
    } catch (error: Throwable) {
        failures++
        println("  FAIL  $name")
        println("        ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, what: String) {
    if (!condition) throw AssertionError(what)
}

private fun expectEq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what: expected <$expected> got <$actual>")
}

private fun expectContains(text: String, fragment: String, what: String) {
    if (!text.contains(fragment, ignoreCase = true)) {
        throw AssertionError("$what: <$fragment> not found in <${text.take(300)}>")
    }
}

private fun expectAbsent(text: String, fragment: String, what: String) {
    if (text.contains(fragment, ignoreCase = true)) {
        throw AssertionError("$what: <$fragment> should not appear in <${text.take(300)}>")
    }
}

/** A context whose files live in a directory this test owns. */
private class TempContext(private val dir: File) : android.content.Context() {
    override val applicationContext: android.content.Context get() = this
    override val filesDir: File get() = dir
}

private fun freshContext(): Pair<TempContext, File> {
    val dir = File.createTempFile("lucifer-ledger", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }
    dir.deleteOnExit()
    return TempContext(dir) to File(dir, "cognition/runs.jsonl")
}

private class OneShotPlanner(private val steps: List<String>) : CognitionPlanner {
    private var index = 0

    override suspend fun step(
        goal: String,
        memory: WorkingMemory,
        tools: List<ToolSpec>,
        lastObservation: String?
    ): String = steps.getOrElse(index++) { "THOUGHT: done\nANSWER: A complete answer, for the record." }

    override suspend fun repair(
        goal: String,
        draft: String,
        findings: List<Finding>,
        memory: WorkingMemory
    ): String? = null
}

private class FixedToolbox(private val spec: ToolSpec, private val result: ToolResult) : CognitionToolbox {
    override fun specs(): List<ToolSpec> = listOf(spec)

    override suspend fun run(call: ToolCall): ToolResult = result
}

private const val PAGE_BODY = "SECRET-PAGE-BODY the private page text that must never be persisted"

private fun runOnce(goal: String = "read the page and summarise it"): CognitionOutcome {
    val planner = OneShotPlanner(
        listOf(
            "THOUGHT: read it\nTOOL: page_read(\"https://example.com/private\")",
            "THOUGHT: done\nANSWER: The page says $PAGE_BODY, which answers the question fully."
        )
    )
    val toolbox = FixedToolbox(
        ToolSpec(ToolCatalogue.PAGE, "fetch a page", SideEffect.NETWORK_READ, "url", 400),
        ToolResult.ok(PAGE_BODY, "fetched")
    )
    return runBlocking {
        CognitionRunner.run(goal = goal, planner = planner, toolbox = toolbox)
    }
}

fun main() {
    println("Lucifer cognition ledger — what a run leaves behind")

    test("a run is recorded as one JSON line") {
        val (context, file) = freshContext()
        val outcome = runOnce()
        CognitionLedger.record(context, outcome, "read the page and summarise it")
        expect(file.isFile, "the ledger file exists")
        val lines = file.readLines().filter { it.isNotBlank() }
        expectEq(lines.size, 1, "one line")
        val parsed = JSONObject(lines.first())
        expectEq(parsed.optInt("steps"), outcome.steps, "steps")
        expectEq(parsed.optInt("toolCalls"), outcome.toolCalls, "tool calls")
        expectEq(parsed.optString("termination"), outcome.stop.termination.name, "termination")
        expectEq(parsed.optString("confidence"), outcome.audit.confidence.name, "confidence")
        expectEq(parsed.optBoolean("concluded"), outcome.concluded, "concluded")
        expect(parsed.optLong("at") > 0, "it is timestamped")
    }

    test("what a tool returned is never written down") {
        val (context, file) = freshContext()
        CognitionLedger.record(context, runOnce(), "read the page and summarise it")
        val raw = file.readText()
        expectAbsent(raw, "SECRET-PAGE-BODY", "the page body never reaches the ledger")
        expectAbsent(raw, "private the private page", "no fragment of it either")
        // The tool's *name* is recorded, because "what did he do" is the question the ledger answers.
        expectContains(raw, "\"page_read\"", "the tool is named")
    }

    test("the goal is truncated, not stored whole") {
        val (context, file) = freshContext()
        val goal = "g".repeat(400)
        CognitionLedger.record(context, runOnce(), goal)
        val parsed = JSONObject(file.readLines().first { it.isNotBlank() })
        expectEq(parsed.optString("goal").length, 160, "the goal is capped at 160 characters")
    }

    test("an interrupted run is recorded as the owner's decision, not as a failure") {
        val (context, file) = freshContext()
        CognitionLedger.recordInterrupted(context, "something long", steps = 3, elapsedMs = 12_000)
        val parsed = JSONObject(file.readLines().first { it.isNotBlank() })
        expectEq(parsed.optString("termination"), "CANCELLED", "termination")
        expectEq(parsed.optBoolean("concluded"), false, "not concluded")
        expectContains(parsed.optString("stopDetail"), "stopped by the owner", "the detail says who stopped it")
        expectContains(CognitionLedger.summary(context), "stopped by you", "the summary counts it separately")
    }

    test("a cancelled model call is not counted as the owner stopping it") {
        val (context, file) = freshContext()
        // The runner's own CANCELLED paths: a planner that throws, and an unavailable route.
        val throwing = object : CognitionPlanner {
            override suspend fun step(
                goal: String,
                memory: WorkingMemory,
                tools: List<ToolSpec>,
                lastObservation: String?
            ): String = error("the model call failed")

            override suspend fun repair(
                goal: String,
                draft: String,
                findings: List<Finding>,
                memory: WorkingMemory
            ): String? = null
        }
        val failed = runBlocking {
            CognitionRunner.run(
                goal = "a question",
                planner = throwing,
                toolbox = FixedToolbox(ToolCatalogue.specs().first(), ToolResult.ok("x"))
            )
        }
        expectEq(failed.stop.termination.name, "CANCELLED", "the run is recorded as cancelled")
        CognitionLedger.record(context, failed, "a question")
        val recorded = JSONObject(file.readLines().first { it.isNotBlank() })
        expectEq(recorded.optBoolean("interrupted"), false, "but not as interrupted by the owner")
        expectAbsent(CognitionLedger.summary(context), "stopped by you", "and the summary does not claim it")
        expectContains(CognitionLedger.summary(context), "1 run(s)", "it is still counted as a run")
    }

    test("the panel line is short, dated and readable") {
        val (context, _) = freshContext()
        CognitionLedger.record(context, runOnce(), "read the page")
        val lines = CognitionLedger.recentLines(context)
        expectEq(lines.size, 1, "one line")
        val line = lines.first()
        expect(Regex("""^\d\d:\d\d · \d+ steps? · answered · (none|low|medium|high)$""").matches(line), "shaped as advertised: <$line>")
    }

    test("an empty ledger says so instead of showing a zero") {
        val (context, _) = freshContext()
        expectEq(CognitionLedger.summary(context), "no deep runs recorded yet", "summary")
        expectEq(CognitionLedger.count(context), 0, "count")
        expectEq(CognitionLedger.recent(context).size, 0, "recent")
        expectEq(CognitionLedger.recentLines(context).size, 0, "recent lines")
    }

    test("a corrupt line does not poison the summary or the panel") {
        val (context, file) = freshContext()
        CognitionLedger.record(context, runOnce(), "first")
        file.appendText("{not json at all\n")
        CognitionLedger.record(context, runOnce(), "second")
        val summary = CognitionLedger.summary(context)
        expectContains(summary, "2 run(s)", "the two real runs are counted: <$summary>")
        val lines = CognitionLedger.recentLines(context)
        expectEq(lines.size, 2, "the corrupt line is skipped")
        expect(lines.all { it.contains("·") }, "every remaining line renders")
    }

    test("the ledger rotates instead of growing without limit") {
        val (context, file) = freshContext()
        file.parentFile?.mkdirs()
        // Padded so the fixture is genuinely past the 256 KB cap without writing 8 000 lines.
        val padding = "x".repeat(1_000)
        val filler = (1..400).map { index ->
            """{"at":1,"steps":$index,"termination":"ANSWERED","concluded":true,"note":"$padding"}"""
        }
        file.writeText(filler.joinToString("\n", postfix = "\n"))
        expect(file.length() > CognitionLedger.MAX_BYTES, "the fixture is over the cap: ${file.length()}")
        CognitionLedger.record(context, runOnce(), "after rotation")
        expect(file.length() < CognitionLedger.MAX_BYTES, "the file came back under the cap: ${file.length()}")
        expectContains(file.readText(), "after rotation", "the newest run survived")
        expect(file.readLines().filter { it.isNotBlank() }.size >= 50, "a minimum history is kept")
    }

    test("count and clear behave") {
        val (context, file) = freshContext()
        CognitionLedger.record(context, runOnce(), "one")
        CognitionLedger.record(context, runOnce(), "two")
        expectEq(CognitionLedger.count(context), 2, "counted")
        CognitionLedger.clear(context)
        expectEq(CognitionLedger.count(context), 0, "cleared")
        expect(!file.exists(), "the file is gone, not emptied")
        expectEq(CognitionLedger.summary(context), "no deep runs recorded yet", "and the summary says so")
    }

    println()
    if (failures == 0) {
        println("PASS · $checks checks")
    } else {
        println("FAIL · ${checks - failures} passed, $failures failed")
        kotlin.system.exitProcess(1)
    }
}
