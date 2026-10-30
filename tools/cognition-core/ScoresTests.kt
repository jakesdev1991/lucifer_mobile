package com.lucifer.mobile.cognition.harness

import com.lucifer.mobile.cognition.CognitionScores
import com.lucifer.mobile.cognition.StepRecord
import com.lucifer.mobile.cognition.ToolCall
import com.lucifer.mobile.cognition.ToolResult
import org.json.JSONObject
import java.io.File

/**
 * The tool tally, executed.
 *
 * `CognitionScores` is what makes the loop's own history change its behaviour: a tool that keeps failing
 * on this device produces a line in the planner's instructions, and the loop stops spending its budget
 * on it. It needs `org.json` and a filesystem, so it had never been compiled or run.
 *
 * The rule that matters most is restraint: below [ToolRecord.MIN_CALLS] calls there is nothing to say,
 * and a model handed a statistic about one bad call will over-fit to it.
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

private class TempContext(private val dir: File) : android.content.Context() {
    override val applicationContext: android.content.Context get() = this
    override val filesDir: File get() = dir
}

private fun freshContext(): Pair<TempContext, File> {
    val dir = File.createTempFile("lucifer-scores", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }
    dir.deleteOnExit()
    return TempContext(dir) to File(dir, "cognition/tools.json")
}

private fun trace(tool: String, ok: Boolean, output: String = "something", at: Int = 0): StepRecord =
    StepRecord(at, "a reason", ToolCall(tool, "arg"), if (ok) ToolResult.ok(output) else ToolResult.failed("no"))

fun main() {
    println("Lucifer tool tally — the loop's own record")

    test("a finished run is tallied and shown") {
        val (context, file) = freshContext()
        CognitionScores.record(context, listOf(trace("page_read", true), trace("clock", true), trace("clock", false)))
        expect(file.isFile, "the tally file exists")
        val report = CognitionScores.report(context)
        expectContains(report, "page_read 1/1", "the report counts it")
        expectContains(report, "clock 1/2", "and the failures")
        val root = JSONObject(file.readText())
        expect(root.optJSONArray("clock") != null, "stored as [ok, calls]")
        expectEq(root.optJSONArray("clock")?.optInt(0), 1, "ok count")
        expectEq(root.optJSONArray("clock")?.optInt(1), 2, "call count")
    }

    test("a call that returned nothing does not count as a success") {
        val (context, _) = freshContext()
        CognitionScores.record(context, listOf(trace("page_read", true, output = "")))
        expectContains(CognitionScores.report(context), "page_read 0/1", "empty output is a failure")
    }

    test("a step with no result or no call is not counted") {
        val (context, _) = freshContext()
        val answered = StepRecord(0, "done", null, null)
        val unfinished = StepRecord(1, "reading", ToolCall("page_read", "x"), null)
        CognitionScores.record(context, listOf(answered, unfinished))
        expectEq(CognitionScores.report(context), "", "nothing was actually run")
    }

    test("two bad calls are not advice — three are") {
        val (context, _) = freshContext()
        CognitionScores.record(context, listOf(trace("page_read", false, at = 0), trace("page_read", false, at = 1)))
        expectEq(CognitionScores.adviceLine(context), "", "two calls is noise")
        CognitionScores.record(context, listOf(trace("page_read", false, at = 2)))
        val advice = CognitionScores.adviceLine(context)
        expectContains(advice, "page_read failed 3 of 3", "three failures is a warning")
        expectContains(advice, "do not retry", "and it says what to do")
    }

    test("a tool that works is never mentioned in the advice") {
        val (context, _) = freshContext()
        CognitionScores.record(context, List(5) { trace("clock", true, at = it) })
        expectEq(CognitionScores.adviceLine(context), "", "no praise line, no noise")
        expectContains(CognitionScores.report(context), "clock 5/5", "but it is in the table")
    }

    test("a corrupt count restarts instead of breaking the turn") {
        val (context, file) = freshContext()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")
        expectEq(CognitionScores.report(context), "", "unreadable is empty, not a crash")
        expectEq(CognitionScores.adviceLine(context), "", "and gives no advice")
        CognitionScores.record(context, listOf(trace("clock", true)))
        expectContains(CognitionScores.report(context), "clock 1/1", "the count restarts cleanly")
    }

    test("the record cannot grow without limit") {
        val (context, file) = freshContext()
        val many = (1..40).map { trace("tool_$it", true, at = it) }
        CognitionScores.record(context, many)
        val root = JSONObject(file.readText())
        val stored = root.keys().asSequence().count()
        expectEq(stored, 24, "capped at 24 tools")
        expect(file.length() < 2_048, "and the file stays tiny: ${file.length()}")
    }

    test("the panel is the summary, the recent runs and the table") {
        val (context, _) = freshContext()
        val panel = CognitionScores.panelLines(context)
        expectContains(panel, "no deep runs recorded yet", "the empty state is stated")
        CognitionScores.record(context, listOf(trace("clock", true)))
        expectContains(CognitionScores.panelLines(context), "tools: clock 1/1", "the table appears")
    }

    test("clearing forgets the cached advice") {
        val (context, _) = freshContext()
        CognitionScores.record(context, List(3) { trace("page_read", false, at = it) })
        expect(CognitionScores.adviceLine(context).isNotBlank(), "advice is cached after the run")
        CognitionScores.clear(context)
        expectEq(CognitionScores.adviceLine(context), "", "clearing drops the cache too")
        expectEq(CognitionScores.report(context), "", "and the table")
    }

    println()
    if (failures == 0) {
        println("PASS · $checks checks")
    } else {
        println("FAIL · ${checks - failures} passed, $failures failed")
        kotlin.system.exitProcess(1)
    }
}
